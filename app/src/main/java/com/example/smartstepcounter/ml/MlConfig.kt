package com.example.smartstepcounter.ml

/**
 * Contract between the model and the app. The same values live in ml/model_config.json,
 * which ml/train.py reads; MlConfigTest fails if the two drift apart.
 */
object MlConfig {
    const val MODEL_FILE = "activity_classifier.tflite"
    const val LABELS_FILE = "labels.txt"

    /** Input normalisation: dynamic acceleration (m/s²) is divided by this constant. */
    const val INPUT_SCALE = 20f

    /** Window length in samples: 1 s at ~50 Hz (SENSOR_DELAY_GAME). */
    const val WINDOW = 50

    /** A new window is classified every [HOP] samples (0.5 s, 50 % overlap). */
    const val HOP = 25

    /** Majority vote over this many latest predictions. */
    const val SMOOTHING = 3

    fun normalize(window: FloatArray): FloatArray = FloatArray(window.size) { window[it] / INPUT_SCALE }
}
