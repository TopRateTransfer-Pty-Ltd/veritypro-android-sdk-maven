package com.example.veritypro_sdk.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


class DocumentDetector(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var inputHeight: Int = 0
    private var inputWidth: Int = 0
    private var inputChannels: Int = 3 // Default to RGB

    init {
        try {
            interpreter = Interpreter(loadModelFile())

            val inputTensor = interpreter!!.getInputTensor(0)
            val inputShape = inputTensor.shape()

            if (inputShape.size != 4) {
                throw IllegalStateException(
                    "Invalid input tensor shape: ${inputShape.contentToString()}"
                )
            }

            inputHeight = inputShape[1]
            inputWidth = inputShape[2]
            inputChannels = inputShape[3]

            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape()

            if (!(outputShape.size == 3 && outputShape[1] == 5)) {
                throw IllegalStateException(
                    "Unexpected YOLO output shape: ${outputShape.contentToString()}"
                )
            }

            Log.d(
                "DocumentDetector",
                "YOLO model loaded successfully. Input=[$inputHeight,$inputWidth,$inputChannels], Output=${outputShape.contentToString()}"
            )

        } catch (e: Exception) {
            Log.e("DocumentDetector", "Failed to initialize DocumentDetector", e)
        }
    }


    @Throws(IOException::class)
    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("best.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun isDocument(bitmap: Bitmap, confThreshold: Float): Pair<Boolean, Float> {
        val inputBuffer = preprocess(bitmap)

        val output = Array(1) { Array(5) { FloatArray(8400) } }

        try {
            interpreter!!.run(inputBuffer, output)
        } catch (e: Exception) {
            Log.e("DocumentDetector", "Inference failed", e)
            return Pair(false, 0f)
        }

        val confidences = mutableListOf<Float>()

        for (i in 0 until 8400) {
            val confidence = output[0][4][i]
            if (confidence >= confThreshold) {
                confidences.add(confidence)
            }
        }

        if (confidences.isEmpty()) {
            return Pair(false, 0f)
        }

        val bestConfidence = confidences.maxOrNull() ?: 0f
        return Pair(true, bestConfidence)
    }



    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        if (inputHeight == 0 || inputWidth == 0) {
            throw IllegalStateException("Input dimensions not set")
        }

        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        val byteBuffer = ByteBuffer.allocateDirect(4 * inputHeight * inputWidth * inputChannels)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputHeight * inputWidth)
        resized.getPixels(intValues, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        var pixel = 0
        for (i in 0 until inputHeight) {
            for (j in 0 until inputWidth) {
                val value = intValues[pixel++]
                byteBuffer.putFloat(((value shr 16 and 0xFF) / 255f))
                byteBuffer.putFloat(((value shr 8 and 0xFF) / 255f))
                byteBuffer.putFloat(((value and 0xFF) / 255f))
                if (inputChannels == 4) {
                    byteBuffer.putFloat(((value shr 24 and 0xFF) / 255f))
                }
            }
        }
        return byteBuffer
    }


    fun close() {
        interpreter?.close()
    }
}

