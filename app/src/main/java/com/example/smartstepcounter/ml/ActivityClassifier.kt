package com.example.smartstepcounter.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite activity classifier: [MlConfig.WINDOW] samples -> 4 class probabilities.
 *
 * The model is memory-mapped straight from the APK (assets are stored uncompressed, see
 * androidResources.noCompress in build.gradle.kts), so it is not copied into the Java heap.
 * Tensor shapes are checked on load: a model exported for another contract fails here,
 * not with garbage predictions later.
 *
 * Interpreter is not thread-safe: [classify] and [close] are serialised on this object.
 */
class ActivityClassifier(context: Context) : Classifier {
    private val interpreter = Interpreter(loadModelFile(context), Interpreter.Options().setNumThreads(1))

    val inputShape: IntArray = interpreter.getInputTensor(0).shape()
    val outputShape: IntArray = interpreter.getOutputTensor(0).shape()

    private val input = Array(1) { FloatArray(MlConfig.WINDOW) }
    private val output = Array(1) { FloatArray(AiLabel.entries.size) }
    private var closed = false

    init {
        try {
            checkTensor("input", inputShape, interpreter.getInputTensor(0).dataType(), intArrayOf(1, MlConfig.WINDOW))
            checkTensor("output", outputShape, interpreter.getOutputTensor(0).dataType(), intArrayOf(1, AiLabel.entries.size))
            val labels = context.assets.open(MlConfig.LABELS_FILE).bufferedReader().readLines().filter { it.isNotBlank() }
            check(labels == AiLabel.entries.map { it.name }) { "labels.txt $labels does not match ${AiLabel.entries}" }
        } catch (e: Exception) {
            interpreter.close()
            throw e
        }
        Log.i(TAG, "model loaded: input ${inputShape.contentToString()}, output ${outputShape.contentToString()}")
    }

    @Synchronized
    override fun classify(window: FloatArray): Prediction {
        check(!closed) { "classifier is closed" }
        require(window.size == MlConfig.WINDOW) { "window must have ${MlConfig.WINDOW} samples, got ${window.size}" }
        MlConfig.normalize(window).copyInto(input[0])
        val start = System.nanoTime()
        interpreter.run(input, output)
        val inferenceMs = (System.nanoTime() - start) / 1e6f
        return Prediction.fromProbabilities(output[0].toList(), inferenceMs)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        interpreter.close()
    }

    companion object {
        const val TAG = "AI"

        fun loadModelFile(context: Context): MappedByteBuffer =
            context.assets.openFd(MlConfig.MODEL_FILE).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    // The mapping stays valid after the channel is closed.
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }

        /** Null instead of a crash: missing asset, wrong shapes, no native library. */
        fun tryCreate(context: Context): Classifier? = try {
            ActivityClassifier(context)
        } catch (e: Exception) {
            Log.e(TAG, "ML unavailable: ${e.message}", e)
            null
        } catch (e: LinkageError) {
            // UnsatisfiedLinkError: the TFLite native library is missing for this ABI.
            Log.e(TAG, "ML unavailable: ${e.message}", e)
            null
        }

        private fun checkTensor(name: String, shape: IntArray, type: DataType, expected: IntArray) {
            check(shape.contentEquals(expected) && type == DataType.FLOAT32) {
                "$name tensor is ${shape.contentToString()} $type, expected ${expected.contentToString()} FLOAT32"
            }
        }
    }
}
