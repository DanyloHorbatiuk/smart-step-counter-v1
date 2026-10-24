package com.example.smartstepcounter.ml

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The model contract must match on both sides: ml/train.py reads ml/model_config.json,
 * the app uses [MlConfig]. A mismatch in the normalisation constant would silently feed
 * the model inputs of the wrong scale.
 */
class MlConfigTest {
    /** Gradle runs unit tests from the module directory (app/). */
    private fun repoFile(path: String): File =
        listOf(File("../$path"), File(path)).firstOrNull { it.exists() }
            ?: error("$path not found from ${File(".").absolutePath}")

    private val config = JsonParser.parseString(repoFile("ml/model_config.json").readText()).asJsonObject

    @Test
    fun normalisationConstantMatchesTrainingScript() {
        assertEquals(config["input_scale"].asFloat, MlConfig.INPUT_SCALE, 0f)
    }

    @Test
    fun windowAndHopMatch() {
        assertEquals(config["window"].asInt, MlConfig.WINDOW)
        assertEquals(config["hop"].asInt, MlConfig.HOP)
    }

    @Test
    fun labelsMatchEnumAndAsset() {
        val labels = config["labels"].asJsonArray.map { it.asString }
        assertEquals(labels, AiLabel.entries.map { it.name })
        val asset = repoFile("app/src/main/assets/${MlConfig.LABELS_FILE}").readLines().filter { it.isNotBlank() }
        assertEquals(labels, asset)
    }

    @Test
    fun normalizeDividesByScale() {
        val window = floatArrayOf(0f, 20f, -10f, 5f)
        assertArrayEquals(floatArrayOf(0f, 1f, -0.5f, 0.25f), MlConfig.normalize(window), 1e-6f)
    }
}
