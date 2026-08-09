package com.rawsmusic.separation

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.File
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.LinkedHashMap


internal fun isCompatibleOnnxGraphShape(
    actualShape: LongArray,
    runtimeShape: List<Long>,
): Boolean {
    if (actualShape.size != runtimeShape.size) return false
    return actualShape.indices.all { index ->
        val actual = actualShape[index]
        val expected = runtimeShape[index]
        actual == expected || (index == 0 && expected == 1L && actual <= 0L)
    }
}

/**
 * Reflection-isolated ONNX Runtime adapter.
 *
 * Keeping the Java API behind reflection allows RawSMusic to swap the development AAR for a
 * reduced-operator AAR without coupling the separation task or native DSP code to ORT classes.
 */
class AiOnnxRuntimeSession private constructor(
    private val environment: Any,
    private val sessionOptions: Any,
    private val session: Any,
    private val inputTensor: Any,
    private val outputTensor: Any,
    val inputBuffer: ByteBuffer,
    val outputBuffer: ByteBuffer,
    private val inputName: String,
    private val outputName: String,
    private val runPinnedMethod: Method,
) : Closeable {
    private val runLock = Any()
    private val inputs = LinkedHashMap<String, Any>(1).apply {
        put(inputName, inputTensor)
    }
    private val outputs = LinkedHashMap<String, Any>(1).apply {
        put(outputName, outputTensor)
    }
    @Volatile private var lastRunError: String = ""
    @Volatile private var closed = false

    /** Called synchronously by native DSP after it fills [inputBuffer]. */
    @Suppress("unused")
    fun runModelFromNative(): Boolean = synchronized(runLock) {
        if (closed) {
            lastRunError = "ONNX Runtime session 已关闭"
            return false
        }
        lastRunError = ""
        runCatching {
            inputBuffer.position(0)
            outputBuffer.position(0)
            val result = runPinnedMethod.invoke(session, inputs, outputs)
            closeReflective(result)
            outputBuffer.position(0)
        }.onFailure { error ->
            val cause = error.cause ?: error
            lastRunError = cause.message ?: cause.javaClass.simpleName
        }.isSuccess
    }

    @Suppress("unused")
    fun lastErrorForNative(): String = lastRunError

    override fun close() {
        synchronized(runLock) {
            if (closed) return
            closed = true
            closeReflective(inputTensor)
            closeReflective(outputTensor)
            closeReflective(session)
            closeReflective(sessionOptions)
        }
    }

    companion object {
        private const val ENV_CLASS = "ai.onnxruntime.OrtEnvironment"
        private const val OPTIONS_CLASS = "ai.onnxruntime.OrtSession\$SessionOptions"
        private const val TENSOR_CLASS = "ai.onnxruntime.OnnxTensor"
        private const val TAG = "AiOnnxRuntime"

        fun runtimeDetails(context: Context): Result<String> = runCatching {
            val runtimeEntry = AiOnnxRuntimeLoader.ensureLoaded(context).getOrThrow()
            val envClass = Class.forName(ENV_CLASS)
            envClass.getMethod("getEnvironment").invoke(null)
            val implementation = envClass.`package`?.implementationVersion.orEmpty()
            if (implementation.isBlank()) {
                "${runtimeEntry.name} ${runtimeEntry.version}"
            } else {
                "ONNX Runtime $implementation · ${runtimeEntry.abi}"
            }
        }

        fun probeModel(
            context: Context,
            modelFile: File,
            contract: AiSeparationModelContract,
        ): Result<Unit> = runCatching {
            open(context, modelFile, contract).use { session ->
                session.inputBuffer.clear()
                while (session.inputBuffer.remaining() >= Float.SIZE_BYTES) {
                    session.inputBuffer.putFloat(0.0f)
                }
                session.inputBuffer.rewind()
                require(session.runModelFromNative()) { session.lastErrorForNative() }
                val output = session.outputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                while (output.hasRemaining()) {
                    require(output.get().isFinite()) { "模型零输入自检输出包含 NaN/Inf" }
                }
            }
        }

        fun open(
            context: Context,
            modelFile: File,
            contract: AiSeparationModelContract,
        ): AiOnnxRuntimeSession {
            AiOnnxRuntimeLoader.ensureLoaded(context).getOrThrow()
            require(modelFile.isFile && modelFile.length() > 0L) { "模型文件不可读" }
            val inputElements = contract.tensorElementCount
            val outputElements = contract.outputElementCount
            require(inputElements in 1..Int.MAX_VALUE.toLong() / Float.SIZE_BYTES) {
                "模型输入张量过大"
            }
            require(outputElements in 1..Int.MAX_VALUE.toLong() / Float.SIZE_BYTES) {
                "模型输出张量过大"
            }
            val inputByteCount = Math.multiplyExact(
                inputElements,
                Float.SIZE_BYTES.toLong(),
            ).toInt()
            val outputByteCount = Math.multiplyExact(
                outputElements,
                Float.SIZE_BYTES.toLong(),
            ).toInt()

            val envClass = Class.forName(ENV_CLASS)
            val optionsClass = Class.forName(OPTIONS_CLASS)
            val tensorClass = Class.forName(TENSOR_CLASS)
            val environment = requireNotNull(
                envClass.getMethod("getEnvironment").invoke(null)
            ) { "ONNX Runtime 环境创建失败" }
            val availableProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val workerThreads = maxOf(
                contract.intraOpThreads,
                (availableProcessors - 2).coerceAtLeast(1),
            ).coerceIn(1, minOf(6, availableProcessors))

            var options: Any? = null
            var session: Any? = null
            var backend = ""
            var inputTensor: Any? = null
            var outputTensor: Any? = null
            try {
                val candidates = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(BACKEND_NNAPI)
                    add(BACKEND_XNNPACK)
                    add(BACKEND_CPU)
                }
                var lastBackendError: Throwable? = null
                for (candidate in candidates) {
                    val candidateOptions = runCatching {
                        createSessionOptions(
                            optionsClass = optionsClass,
                            workerThreads = workerThreads,
                            backend = candidate,
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "ORT_PERF backend=$candidate options failed", error)
                    }.getOrNull() ?: continue
                    val candidateSession = runCatching {
                        envClass.getMethod("createSession", String::class.java, optionsClass)
                            .invoke(environment, modelFile.absolutePath, candidateOptions)
                    }.onFailure { error ->
                        lastBackendError = error.cause ?: error
                        Log.w(TAG, "ORT_PERF backend=$candidate session failed", lastBackendError)
                    }.getOrNull()
                    if (candidateSession != null) {
                        options = candidateOptions
                        session = candidateSession
                        backend = candidate
                        break
                    }
                    closeReflective(candidateOptions)
                }
                requireNotNull(session) {
                    "ONNX Runtime 无可用执行后端：${lastBackendError?.message.orEmpty()}"
                }
                requireNotNull(options)
                Log.i(
                    TAG,
                    "ORT_PERF profile=performance backend=$backend workers=$workerThreads " +
                        "available=$availableProcessors",
                )
                val resolvedIo = validateGraph(session, contract)

                val inputBytes = ByteBuffer.allocateDirect(inputByteCount).order(ByteOrder.nativeOrder())
                val outputBytes = ByteBuffer.allocateDirect(outputByteCount).order(ByteOrder.nativeOrder())
                val createTensor = tensorClass.getMethod(
                    "createTensor",
                    envClass,
                    FloatBuffer::class.java,
                    LongArray::class.java,
                )
                inputTensor = createTensor.invoke(
                    null,
                    environment,
                    inputBytes.asFloatBuffer(),
                    contract.inputShape.toLongArray(),
                )
                outputTensor = createTensor.invoke(
                    null,
                    environment,
                    outputBytes.asFloatBuffer(),
                    contract.outputShape.toLongArray(),
                )
                val runPinned = session.javaClass.methods.firstOrNull { method ->
                    method.name == "run" && method.parameterTypes.contentEquals(
                        arrayOf(Map::class.java, Map::class.java)
                    )
                } ?: error("ONNX Runtime 缺少 pinned output run(Map, Map)")

                return AiOnnxRuntimeSession(
                    environment = environment,
                    sessionOptions = options,
                    session = session,
                    inputTensor = inputTensor,
                    outputTensor = outputTensor,
                    inputBuffer = inputBytes,
                    outputBuffer = outputBytes,
                    inputName = resolvedIo.first,
                    outputName = resolvedIo.second,
                    runPinnedMethod = runPinned,
                )
            } catch (error: Throwable) {
                closeReflective(inputTensor)
                closeReflective(outputTensor)
                closeReflective(session)
                closeReflective(options)
                throw error.cause ?: error
            }
        }

        private fun validateGraph(session: Any, contract: AiSeparationModelContract): Pair<String, String> {
            val inputInfo = session.javaClass.getMethod("getInputInfo").invoke(session) as? Map<*, *>
                ?: error("无法读取模型输入信息")
            val outputInfo = session.javaClass.getMethod("getOutputInfo").invoke(session) as? Map<*, *>
                ?: error("无法读取模型输出信息")
            require(inputInfo.size == 1) { "当前只支持单输入模型，实际 ${inputInfo.keys}" }
            require(outputInfo.size == 1) { "当前只支持单输出模型，实际 ${outputInfo.keys}" }
            val actualInput = inputInfo.keys.first()?.toString().orEmpty()
            val actualOutput = outputInfo.keys.first()?.toString().orEmpty()
            require(actualInput.isNotBlank() && actualOutput.isNotBlank()) { "模型输入输出名称为空" }
            if (contract.inputName != "*") {
                require(actualInput == contract.inputName) {
                    "模型输入名称不匹配：期望 ${contract.inputName}，实际 $actualInput"
                }
            }
            if (contract.outputName != "*") {
                require(actualOutput == contract.outputName) {
                    "模型输出名称不匹配：期望 ${contract.outputName}，实际 $actualOutput"
                }
            }
            validateTensorInfo("输入", inputInfo.values.firstOrNull(), contract.inputShape)
            validateTensorInfo("输出", outputInfo.values.firstOrNull(), contract.outputShape)
            return actualInput to actualOutput
        }

        private fun validateTensorInfo(label: String, nodeInfo: Any?, expectedShape: List<Long>) {
            requireNotNull(nodeInfo) { "${label}张量信息为空" }
            val info = nodeInfo.javaClass.getMethod("getInfo").invoke(nodeInfo)
                ?: error("${label}张量类型信息为空")
            require(info.javaClass.name == "ai.onnxruntime.TensorInfo") {
                "${label}节点不是 Tensor"
            }
            val type = requireNotNull(info.javaClass.getField("type").get(info)).toString()
            require(type == "FLOAT") { "${label}张量必须是 float32，实际为 $type" }
            val shape = info.javaClass.getMethod("getShape").invoke(info) as? LongArray
                ?: error("无法读取${label}张量形状")
            Log.i(TAG, "${label}图形状=${shape.toList()}，运行形状=$expectedShape")
            require(isCompatibleOnnxGraphShape(shape, expectedShape)) {
                "${label}张量形状不匹配：运行时要求 $expectedShape，图声明 ${shape.toList()}。" +
                    "仅允许第 0 维使用 -1/符号 batch，其余 C/F/T 必须精确一致"
            }
        }

        private fun closeReflective(value: Any?) {
            if (value == null) return
            runCatching { value.javaClass.getMethod("close").invoke(value) }
        }

        private fun createSessionOptions(
            optionsClass: Class<*>,
            workerThreads: Int,
            backend: String,
        ): Any {
            val options = optionsClass.getConstructor().newInstance()
            try {
                optionsClass.getMethod("setOptimizationLevel", Class.forName(OPT_LEVEL_CLASS))
                    .invoke(
                        options,
                        enumConstant(OPT_LEVEL_CLASS, "ALL_OPT"),
                    )
                optionsClass.getMethod(
                    "setMemoryPatternOptimization",
                    Boolean::class.javaPrimitiveType,
                ).invoke(options, true)
                optionsClass.getMethod("setCPUArenaAllocator", Boolean::class.javaPrimitiveType)
                    .invoke(options, true)
                optionsClass.getMethod(
                    "addConfigEntry",
                    String::class.java,
                    String::class.java,
                ).invoke(
                    options,
                    "session.intra_op.allow_spinning",
                    "1",
                )

                val ortThreads = when (backend) {
                    BACKEND_NNAPI -> {
                        val flagsClass = Class.forName(NNAPI_FLAGS_CLASS)
                        val useNchw = enumConstant(NNAPI_FLAGS_CLASS, "USE_NCHW")
                        val cpuDisabled = enumConstant(NNAPI_FLAGS_CLASS, "CPU_DISABLED")
                        val flags = EnumSet::class.java.getMethod(
                            "of",
                            Enum::class.java,
                            Enum::class.java,
                        ).invoke(null, useNchw, cpuDisabled)
                        optionsClass.getMethod("addNnapi", EnumSet::class.java)
                            .invoke(options, flags)
                        workerThreads
                    }
                    BACKEND_XNNPACK -> {
                        optionsClass.getMethod("addXnnpack", Map::class.java).invoke(
                            options,
                            mapOf("intra_op_num_threads" to workerThreads.toString()),
                        )
                        1
                    }
                    BACKEND_CPU -> workerThreads
                    else -> error("Unknown ORT backend $backend")
                }
                optionsClass.getMethod("setIntraOpNumThreads", Int::class.javaPrimitiveType)
                    .invoke(options, ortThreads)
                optionsClass.getMethod("setInterOpNumThreads", Int::class.javaPrimitiveType)
                    .invoke(options, 1)
                return options
            } catch (error: Throwable) {
                closeReflective(options)
                throw error.cause ?: error
            }
        }

        private fun enumConstant(className: String, name: String): Any =
            requireNotNull(Class.forName(className).enumConstants).first {
                (it as Enum<*>).name == name
            }

        private const val BACKEND_NNAPI = "nnapi_nchw"
        private const val BACKEND_XNNPACK = "xnnpack"
        private const val BACKEND_CPU = "cpu"
        private const val NNAPI_FLAGS_CLASS = "ai.onnxruntime.providers.NNAPIFlags"
        private const val OPT_LEVEL_CLASS =
            "ai.onnxruntime.OrtSession\$SessionOptions\$OptLevel"
    }
}
