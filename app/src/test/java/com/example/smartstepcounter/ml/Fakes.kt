package com.example.smartstepcounter.ml

/** Returns scripted predictions in order (the last one repeats) and records every window. */
class FakeClassifier(private val script: List<Pair<AiLabel, Float>>) : Classifier {
    val windows = mutableListOf<FloatArray>()
    var closed = false
        private set
    var failNext = false

    override fun classify(window: FloatArray): Prediction {
        if (failNext) {
            failNext = false
            throw IllegalArgumentException("boom")
        }
        val (label, confidence) = script[minOf(windows.size, script.lastIndex)]
        windows += window
        return prediction(label, confidence)
    }

    override fun close() {
        closed = true
    }
}

/** [confidence] for [label], the rest spread evenly over the other classes. */
fun prediction(label: AiLabel, confidence: Float, inferenceMs: Float = 0.1f): Prediction {
    val rest = (1f - confidence) / (AiLabel.entries.size - 1)
    val probabilities = AiLabel.entries.map { if (it == label) confidence else rest }
    return Prediction(label, confidence, probabilities, inferenceMs)
}
