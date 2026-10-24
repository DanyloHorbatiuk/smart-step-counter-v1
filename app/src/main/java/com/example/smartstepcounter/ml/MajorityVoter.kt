package com.example.smartstepcounter.ml

/**
 * Smooths predictions: the label is the most frequent one among the last [size].
 * A tie goes to the most recent of the tied labels, so a new activity shows up as soon as
 * it is not outvoted. Probabilities are averaged over the same predictions.
 */
class MajorityVoter(private val size: Int = MlConfig.SMOOTHING) {
    private val recent = ArrayDeque<Prediction>()

    fun add(prediction: Prediction): Prediction {
        recent.addLast(prediction)
        if (recent.size > size) recent.removeFirst()

        val counts = recent.groupingBy { it.label }.eachCount()
        val top = counts.values.max()
        val winner = recent.last { counts.getValue(it.label) == top }.label

        val probabilities = AiLabel.entries.indices.map { i -> recent.map { it.probabilities[i] }.average().toFloat() }
        return Prediction(winner, probabilities[winner.ordinal], probabilities, prediction.inferenceMs)
    }

    fun reset() = recent.clear()
}
