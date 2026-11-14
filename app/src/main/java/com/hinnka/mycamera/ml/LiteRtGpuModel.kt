package com.hinnka.mycamera.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Single-input, single-output FLOAT32 model running through the app's existing TensorFlow Lite
 * GPU delegate. Reusing the existing runtime avoids mixing TensorFlow Lite with the incompatible
 * LiteRT 2.x runtime artifacts while keeping Night Vision GPU-accelerated.
 *
 * Instances are not thread-safe and must be created, run and closed on one thread.
 */
internal class NightVisionGpuModel private constructor(
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate,
    private val inputElementCount: Int,
    private val outputElementCount: Int,
) : AutoCloseable {
    private val inputBuffer = ByteBuffer.allocateDirect(inputElementCount * 4)
        .order(ByteOrder.nativeOrder())
    private val outputBuffer = ByteBuffer.allocateDirect(outputElementCount * 4)
        .order(ByteOrder.nativeOrder())

    fun run(input: FloatArray): FloatArray {
        require(input.size == inputElementCount) {
            "Unexpected input size: " + input.size + ", expected " + inputElementCount
        }
        inputBuffer.clear()
        for (value in input) inputBuffer.putFloat(value)
        inputBuffer.rewind()

        outputBuffer.clear()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()
        return FloatArray(outputElementCount) { outputBuffer.float }
    }

    override fun close() {
        interpreter.close()
        gpuDelegate.close()
    }

    companion object {
        fun fromAsset(
            context: Context,
            assetName: String,
            cache: MlDelegateCache?,
            inputElementCount: Int,
            outputElementCount: Int,
        ): NightVisionGpuModel {
            val modelBuffer = FileUtil.loadMappedFile(context, assetName)
            return create(modelBuffer, cache, inputElementCount, outputElementCount)
        }

        fun fromFile(
            file: File,
            cache: MlDelegateCache?,
            inputElementCount: Int,
            outputElementCount: Int,
        ): NightVisionGpuModel {
            val modelBuffer = FileInputStream(file).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
            }
            return create(modelBuffer, cache, inputElementCount, outputElementCount)
        }

        private fun create(
            modelBuffer: ByteBuffer,
            cache: MlDelegateCache?,
            inputElementCount: Int,
            outputElementCount: Int,
        ): NightVisionGpuModel {
            val compatibility = CompatibilityList()
            check(compatibility.isDelegateSupportedOnThisDevice) {
                "TensorFlow Lite GPU delegate is not supported on this device"
            }

            val delegateOptions = compatibility.bestOptionsForThisDevice
            cache?.let {
                delegateOptions.setSerializationParams(
                    it.directory.absolutePath,
                    it.modelToken,
                )
            }
            val delegate = GpuDelegate(delegateOptions)
            return try {
                val options = Interpreter.Options().apply {
                    setNumThreads(1)
                    addDelegate(delegate)
                }
                val interpreter = Interpreter(modelBuffer, options)
                val inputBytes = interpreter.getInputTensor(0).numBytes()
                val outputBytes = interpreter.getOutputTensor(0).numBytes()
                check(inputBytes == inputElementCount * 4) {
                    "Unexpected input tensor size: " + inputBytes + " bytes"
                }
                check(outputBytes == outputElementCount * 4) {
                    "Unexpected output tensor size: " + outputBytes + " bytes"
                }
                NightVisionGpuModel(
                    interpreter,
                    delegate,
                    inputElementCount,
                    outputElementCount,
                )
            } catch (error: Throwable) {
                delegate.close()
                throw error
            }
        }
    }
}
