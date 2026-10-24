package com.example.smartstepcounter.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class MajorityVoterTest {
    private val voter = MajorityVoter(3)

    @Test
    fun singlePredictionPassesThrough() {
        val p = voter.add(prediction(AiLabel.WALKING, 0.9f))
        assertEquals(AiLabel.WALKING, p.label)
        assertEquals(0.9f, p.confidence, 1e-6f)
    }

    @Test
    fun majorityWinsOverLatestOutlier() {
        voter.add(prediction(AiLabel.WALKING, 0.9f))
        voter.add(prediction(AiLabel.WALKING, 0.8f))
        val p = voter.add(prediction(AiLabel.RUNNING, 0.9f))
        assertEquals(AiLabel.WALKING, p.label)
    }

    @Test
    fun allDifferentTakesLatest() {
        voter.add(prediction(AiLabel.STILL, 0.9f))
        voter.add(prediction(AiLabel.WALKING, 0.9f))
        assertEquals(AiLabel.RUNNING, voter.add(prediction(AiLabel.RUNNING, 0.9f)).label)
    }

    @Test
    fun onlyLastThreeVote() {
        voter.add(prediction(AiLabel.WALKING, 0.9f))
        voter.add(prediction(AiLabel.WALKING, 0.9f))
        voter.add(prediction(AiLabel.RUNNING, 0.9f))
        // Window is now [WALKING, RUNNING, RUNNING].
        assertEquals(AiLabel.RUNNING, voter.add(prediction(AiLabel.RUNNING, 0.9f)).label)
    }

    @Test
    fun probabilitiesAreAveraged() {
        voter.add(prediction(AiLabel.WALKING, 1.0f))
        val p = voter.add(prediction(AiLabel.WALKING, 0.6f))
        assertEquals(0.8f, p.confidence, 1e-6f)
        assertEquals(1f, p.probabilities.sum(), 1e-5f)
    }
}
