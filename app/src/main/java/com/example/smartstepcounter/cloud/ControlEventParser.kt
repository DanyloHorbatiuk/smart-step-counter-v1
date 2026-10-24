package com.example.smartstepcounter.cloud

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A Firebase REST streaming event, decoded. */
sealed interface ControlUpdate {
    /** Replaces the value at [path] ("/" = the whole node) with [data] (null = deleted). */
    data class Put(val path: String, val data: JsonElement?) : ControlUpdate

    /** Merges the children of [data] into the value at [path]. */
    data class Patch(val path: String, val data: JsonObject) : ControlUpdate

    data object KeepAlive : ControlUpdate

    /** cancel / auth_revoked: Firebase closes the stream on its side. */
    data class Failure(val reason: String) : ControlUpdate

    /** Malformed or unknown event; logged and skipped. */
    data class Ignored(val why: String) : ControlUpdate
}

/**
 * Decodes Server-Sent Events of the Firebase REST streaming API:
 * `event: put|patch` with `data: {"path": "...", "data": ...}`,
 * `event: keep-alive` with `data: null`, `event: cancel|auth_revoked`.
 */
object ControlEventParser {
    fun parse(type: String?, data: String): ControlUpdate = when (type) {
        "keep-alive" -> ControlUpdate.KeepAlive
        "cancel" -> ControlUpdate.Failure("cancel: ${data.trim()}")
        "auth_revoked" -> ControlUpdate.Failure("auth_revoked: ${data.trim()}")
        "put", "patch" -> parseChange(type, data)
        else -> ControlUpdate.Ignored("unknown event '$type'")
    }

    private fun parseChange(type: String, raw: String): ControlUpdate {
        val root = try {
            JsonParser.parseString(raw)
        } catch (e: Exception) {
            return ControlUpdate.Ignored("bad JSON: ${e.message}")
        }
        if (!root.isJsonObject) return ControlUpdate.Ignored("not an object: $raw")
        val obj = root.asJsonObject
        val path = obj.get("path")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return ControlUpdate.Ignored("no path: $raw")
        val payload = obj.get("data")?.takeUnless { it.isJsonNull }
        return if (type == "put") {
            ControlUpdate.Put(path, payload)
        } else {
            if (payload == null || !payload.isJsonObject) return ControlUpdate.Ignored("patch without object: $raw")
            ControlUpdate.Patch(path, payload.asJsonObject)
        }
    }
}

/**
 * Applies [ControlUpdate]s to [ControlState] and derives commands. Pure, so the whole
 * Firebase → command path is tested without a network.
 */
object ControlReducer {
    private const val LED = "led_status"
    private const val VIBRATE = "vibrate"

    data class Result(val state: ControlState, val commands: List<ControlCommand>)

    fun reduce(current: ControlState, update: ControlUpdate): Result {
        val next = when (update) {
            is ControlUpdate.Put -> applyPut(current, update.path, update.data)
            is ControlUpdate.Patch -> applyPatch(current, update.path, update.data)
            else -> current
        }
        val commands = buildList {
            if (next.ledStatus != current.ledStatus) add(ControlCommand.SetLed(next.ledStatus))
            // The app resets vibrate itself, so true here is always a new command.
            if (next.vibrate && update.touches(VIBRATE)) add(ControlCommand.Vibrate)
        }
        return Result(next, commands)
    }

    private fun applyPut(current: ControlState, path: String, data: JsonElement?): ControlState =
        when (path.trim('/')) {
            "" -> fromObject(ControlState(), data?.takeIf { it.isJsonObject }?.asJsonObject)
            LED -> current.copy(ledStatus = data.asBool())
            VIBRATE -> current.copy(vibrate = data.asBool())
            else -> current
        }

    private fun applyPatch(current: ControlState, path: String, data: JsonObject): ControlState =
        if (path.trim('/').isEmpty()) fromObject(current, data) else current

    /** Overrides fields of [base] that are present in [obj]. */
    private fun fromObject(base: ControlState, obj: JsonObject?): ControlState {
        if (obj == null) return base
        return base.copy(
            ledStatus = if (obj.has(LED)) obj.get(LED).asBool() else base.ledStatus,
            vibrate = if (obj.has(VIBRATE)) obj.get(VIBRATE).asBool() else base.vibrate,
        )
    }

    /** Accepts true/false and also "true"/1 typed by hand in the console; null/garbage = false. */
    private fun JsonElement?.asBool(): Boolean {
        if (this == null || !isJsonPrimitive) return false
        val p = asJsonPrimitive
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asDouble != 0.0
            p.isString -> p.asString.trim().equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun ControlUpdate.touches(field: String): Boolean = when (this) {
        is ControlUpdate.Put -> path.trim('/').let { it.isEmpty() || it == field }
        is ControlUpdate.Patch -> path.trim('/').isEmpty() && data.has(field)
        else -> false
    }
}

/** Reconnect delays: 1, 2, 4, 8, 16 s, then capped at 30 s. */
object Backoff {
    const val MAX_MS = 30_000L

    fun delayMs(attempt: Int): Long = (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(MAX_MS)
}
