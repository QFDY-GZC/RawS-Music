package com.rawsmusic.ai.melody

import android.content.Context
import com.rawsmusic.separation.AiOnnxRuntimeLoader
import java.io.Closeable
import java.io.File
import java.lang.reflect.Array as JavaArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

internal class RmvpeOnnxSession private constructor(
    private val environment: Any,
    private val options: Any,
    private val session: Any,
    private val tensorClass: Class<*>,
    private val inputName: String,
    private val outputName: String,
    private val contract: AiMelodyModelDescriptor,
) : Closeable {

    fun infer(mel: FloatArray, frameCount: Int): FloatArray {
        require(frameCount > 0 && frameCount % 32 == 0)
        require(mel.size == contract.melBins * frameCount)
        val buffer = ByteBuffer.allocateDirect(mel.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(mel); rewind() }
        val createTensor = tensorClass.getMethod(
            "createTensor",
            environment.javaClass,
            FloatBuffer::class.java,
            LongArray::class.java,
        )
        val tensor = createTensor.invoke(
            null,
            environment,
            buffer,
            longArrayOf(1L, contract.melBins.toLong(), frameCount.toLong()),
        )
        return try {
            val inputs = linkedMapOf<String, Any>(inputName to tensor)
            val run = session.javaClass.methods.firstOrNull { method ->
                method.name == "run" && method.parameterTypes.contentEquals(arrayOf(Map::class.java))
            } ?: error("ONNX Runtime 缺少 run(Map) 方法")
            val result = run.invoke(session, inputs)
            try {
                val value = result.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                    .invoke(result, 0)
                val output = requireNotNull(value.javaClass.getMethod("getValue").invoke(value)) {
                    "RMVPE 输出为空"
                }
                decodeOutput(output, frameCount)
            } finally {
                closeReflective(result)
            }
        } finally {
            closeReflective(tensor)
        }
    }

    private fun decodeOutput(value: Any, expectedFrames: Int): FloatArray {
        val shape = nestedShape(value)
        require(shape.size == 3 && shape[0] == 1) { "RMVPE 输出必须是 [1,T,360]" }
        require(shape[1] >= expectedFrames && shape[2] == contract.classCount) {
            "RMVPE 输出 shape 不匹配：$shape"
        }
        val output = FloatArray(shape[1] * shape[2])
        var cursor = 0
        val batch = JavaArray.get(value, 0)
        repeat(shape[1]) { frame ->
            val row = JavaArray.get(batch, frame)
            repeat(shape[2]) { bin ->
                val number = JavaArray.get(row, bin) as? Number
                    ?: error("RMVPE 输出包含非数值元素")
                output[cursor++] = number.toFloat()
            }
        }
        return output
    }

    override fun close() {
        closeReflective(session)
        closeReflective(options)
    }

    companion object {
        fun open(
            context: Context,
            model: AiInstalledMelodyModel,
        ): RmvpeOnnxSession {
            AiOnnxRuntimeLoader.ensureLoaded(context).getOrThrow()
            require(model.modelFile.isFile) { "RMVPE 模型不存在" }
            val environmentClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val optionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            val tensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
            val environment = requireNotNull(environmentClass.getMethod("getEnvironment").invoke(null)) {
                "ONNX Runtime 环境为空"
            }
            val options = optionsClass.getConstructor().newInstance()
            val workers = min(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            runCatching {
                optionsClass.getMethod("setIntraOpNumThreads", Int::class.javaPrimitiveType)
                    .invoke(options, workers)
                optionsClass.getMethod("setInterOpNumThreads", Int::class.javaPrimitiveType)
                    .invoke(options, 1)
            }
            val session = try {
                environmentClass.getMethod("createSession", String::class.java, optionsClass)
                    .invoke(environment, model.modelFile.absolutePath, options)
            } catch (error: Throwable) {
                closeReflective(options)
                throw error.cause ?: error
            }
            try {
                val inputInfo = session.javaClass.getMethod("getInputInfo").invoke(session) as Map<*, *>
                val outputInfo = session.javaClass.getMethod("getOutputInfo").invoke(session) as Map<*, *>
                require(inputInfo.size == 1 && outputInfo.size == 1) { "RMVPE 必须是单输入单输出模型" }
                val inputName = inputInfo.keys.single().toString()
                val outputName = outputInfo.keys.single().toString()
                val inputShape = tensorShape(requireNotNull(inputInfo.values.single()), "输入")
                val outputShape = tensorShape(requireNotNull(outputInfo.values.single()), "输出")
                validateGraph(inputShape, outputShape, model.descriptor)
                return RmvpeOnnxSession(
                    environment = environment,
                    options = options,
                    session = session,
                    tensorClass = tensorClass,
                    inputName = inputName,
                    outputName = outputName,
                    contract = model.descriptor,
                )
            } catch (error: Throwable) {
                closeReflective(session)
                closeReflective(options)
                throw error.cause ?: error
            }
        }

        private fun tensorShape(nodeInfo: Any, label: String): LongArray {
            val info = requireNotNull(nodeInfo.javaClass.getMethod("getInfo").invoke(nodeInfo)) {
                "RMVPE $label tensor info 为空"
            }
            require(info.javaClass.name == "ai.onnxruntime.TensorInfo") { "RMVPE ${label}不是 Tensor" }
            val type = requireNotNull(info.javaClass.getField("type").get(info)).toString()
            require(type == "FLOAT") { "RMVPE ${label}必须是 float32，实际=$type" }
            return info.javaClass.getMethod("getShape").invoke(info) as LongArray
        }

        internal fun validateGraph(
            inputShape: LongArray,
            outputShape: LongArray,
            contract: AiMelodyModelDescriptor,
        ) {
            require(inputShape.size == 3) { "RMVPE 输入 rank 必须为 3" }
            require(inputShape[0] <= 0L || inputShape[0] == 1L) { "RMVPE batch 必须为 1" }
            require(inputShape[1] <= 0L || inputShape[1] == contract.melBins.toLong()) {
                "RMVPE mel 维度不匹配"
            }
            require(outputShape.size == 3) { "RMVPE 输出 rank 必须为 3" }
            require(outputShape[0] <= 0L || outputShape[0] == 1L) { "RMVPE 输出 batch 必须为 1" }
            require(outputShape[2] <= 0L || outputShape[2] == contract.classCount.toLong()) {
                "RMVPE pitch class 数不匹配"
            }
            val inputFrames = inputShape[2]
            val outputFrames = outputShape[1]
            if (inputFrames > 0L && outputFrames > 0L) {
                require(inputFrames == outputFrames) { "RMVPE 输入输出帧数不一致" }
            }
        }

        private fun nestedShape(value: Any): List<Int> {
            if (!value.javaClass.isArray) return emptyList()
            val length = JavaArray.getLength(value)
            if (length == 0) return listOf(0)
            return listOf(length) + nestedShape(requireNotNull(JavaArray.get(value, 0)))
        }

        private fun closeReflective(value: Any?) {
            if (value == null) return
            runCatching { value.javaClass.getMethod("close").invoke(value) }
        }
    }
}
