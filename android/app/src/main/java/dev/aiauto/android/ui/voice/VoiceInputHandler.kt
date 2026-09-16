package dev.aiauto.android.ui.voice

/**
 * 功能用途：封装系统 [android.speech.SpeechRecognizer]，提供按需语音输入能力，
 * 包含权限预检、服务可用性检查、识别结果回调与生命周期释放。
 *
 * 设计说明：
 * - 识别结果（partial/final）与错误统一通过构造参数 [onResult] 回调，调用方按 [VoiceInputResult] 分支处理。
 * - 本类不长期持有 Activity 引用：[startListening] 接收 Context 后立即改用 applicationContext
 *   创建底层识别器，自身字段只保留 [SpeechRecognizer] 与回调，避免 Context 泄漏。
 * - 系统语音识别可能由设备本地引擎或云端服务处理音频，详见 [VOICE_PROCESSING_NOTICE]，
 *   本封装不宣称纯离线识别。
 */

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * 语音识别结果密封接口：区分中间结果、最终结果与错误。
 */
sealed interface VoiceInputResult {
    /** 部分识别结果（实时中间文本，用于边说边显示）。 */
    data class Partial(val text: String) : VoiceInputResult

    /** 最终识别结果（一句话识别完成后返回）。 */
    data class Final(val text: String) : VoiceInputResult

    /**
     * 识别错误。
     *
     * [code] 取值：
     * - 系统错误码：[SpeechRecognizer.ERROR_AUDIO] 等（见 [SpeechRecognizer] 常量）。
     * - 本模块自定义错误码：[VOICE_ERROR_SERVICE_UNAVAILABLE]、[VOICE_ERROR_PERMISSION_MISSING]、[VOICE_ERROR_NOT_READY]。
     *
     * [message] 为面向用户可读的中文描述。
     */
    data class Error(val code: Int, val message: String) : VoiceInputResult
}

/** 自定义错误码：当前设备没有可用的语音识别服务（未安装识别器或被系统禁用）。 */
const val VOICE_ERROR_SERVICE_UNAVAILABLE = 10001

/** 自定义错误码：调用开始识别时缺少 RECORD_AUDIO 录音权限。 */
const val VOICE_ERROR_PERMISSION_MISSING = 10002

/** 自定义错误码：识别器尚未就绪或已被销毁，不能执行该操作。 */
const val VOICE_ERROR_NOT_READY = 10003

/**
 * 透明说明常量：告知调用方系统语音识别的音频处理路径。
 *
 * 系统 SpeechRecognizer 可能将音频发送到设备本地引擎或云端服务进行识别，
 * 本封装不保证也不宣称纯离线识别能力；集成方在 UI 上展示语音入口时应附带该说明。
 */
const val VOICE_PROCESSING_NOTICE =
    "系统语音识别可能由设备本地引擎或云端服务处理音频，不保证纯离线运行。"

/**
 * 按需语音输入处理器。
 *
 * 典型用法：
 * ```
 * val handler = VoiceInputHandler { result -> /* 更新 UI */ }
 * if (!handler.isRecordAudioPermissionGranted(context)) {
 *     // 调用方应自行发起系统权限请求（ActivityCompat.requestPermissions 等）
 * } else {
 *     handler.startListening(context)
 * }
 * // 用户说完：handler.stopListening()
 * // 放弃识别：handler.cancel()
 * // 宿主销毁：handler.destroy()
 * ```
 *
 * @param onResult 识别结果回调，由系统在主线程触发；调用方如需更新 Compose 状态可直接使用。
 */
class VoiceInputHandler(
    private val onResult: (VoiceInputResult) -> Unit,
) {
    // 底层系统语音识别器；startListening 时创建，destroy 时释放。
    private var speechRecognizer: SpeechRecognizer? = null

    // 标记当前是否正在识别，避免重复启动或重复回调。
    private var isListening: Boolean = false

    /**
     * 检查 RECORD_AUDIO 权限是否已授予。
     *
     * @return true 表示权限已授予，可直接调用 [startListening]；
     *         false 表示调用方需要向系统请求权限（由集成方通过 Activity 发起系统授权弹窗）。
     */
    fun isRecordAudioPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 开始语音识别。
     *
     * 执行步骤：
     * 1. 检查 RECORD_AUDIO 权限；未授予时通过回调返回 [VOICE_ERROR_PERMISSION_MISSING]，不抛异常。
     * 2. 检查设备是否存在可用的语音识别服务；不可用时返回 [VOICE_ERROR_SERVICE_UNAVAILABLE]。
     * 3. 若已有旧识别器先释放，再用 applicationContext 新建 [SpeechRecognizer]。
     * 4. 绑定 [VoiceRecognitionListener]，并通过 [RecognizerIntent] 配置中文识别与部分结果后开始监听。
     *
     * @param context 建议传入 Activity 或 ApplicationContext；方法内部会立即切换到 applicationContext，不长期持有本参数。
     */
    fun startListening(context: Context) {
        // 第一步：权限预检，未授权时直接回调错误，避免系统层崩溃。
        if (!isRecordAudioPermissionGranted(context)) {
            onResult(
                VoiceInputResult.Error(
                    code = VOICE_ERROR_PERMISSION_MISSING,
                    message = "缺少录音权限，请先在系统设置中授予 RECORD_AUDIO 权限。",
                ),
            )
            return
        }

        // 第二步：检查设备是否提供语音识别服务（无服务时直接降级，不崩溃）。
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onResult(
                VoiceInputResult.Error(
                    code = VOICE_ERROR_SERVICE_UNAVAILABLE,
                    message = "当前设备没有可用的语音识别服务，请检查系统语音输入设置。",
                ),
            )
            return
        }

        // 释放旧实例，避免重复绑定系统服务。
        releaseRecognizer()

        // 使用 applicationContext 创建识别器，切断对 Activity 的潜在引用链。
        val appContext = context.applicationContext
        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer.setRecognitionListener(VoiceRecognitionListener())
        speechRecognizer = recognizer

        // 构建识别 Intent：中文普通话 + 部分结果 + 不弹出系统识别 UI。
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            // 开启部分结果，便于实时展示用户正在说的内容。
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 不使用系统自带的全屏识别 UI，由调用方自行管理界面。
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

        isListening = true
        recognizer.startListening(intent)
    }

    /**
     * 停止监听录音并等待系统返回最终结果。
     *
     * 调用后系统结束麦克风采集并尝试给出最终识别结果；
     * 若识别失败仍会走 [RecognitionListener.onError] 回调。
     */
    fun stopListening() {
        val recognizer = speechRecognizer
        if (recognizer == null || !isListening) {
            onResult(
                VoiceInputResult.Error(
                    code = VOICE_ERROR_NOT_READY,
                    message = "语音识别尚未开始，无法停止。",
                ),
            )
            return
        }
        recognizer.stopListening()
    }

    /**
     * 取消当前识别，丢弃已采集音频，不返回最终结果。
     */
    fun cancel() {
        speechRecognizer?.runCatching { cancel() }
        isListening = false
    }

    /**
     * 释放底层 [SpeechRecognizer]，解绑系统服务，避免内存泄漏。
     *
     * 应在宿主 Activity.onDestroy 或 ViewModel.onCleared 中调用。
     * 调用后本实例不可再复用；如需继续使用请新建 [VoiceInputHandler]。
     */
    fun destroy() {
        releaseRecognizer()
    }

    /**
     * 内部释放识别器资源，重复调用安全。
     */
    private fun releaseRecognizer() {
        speechRecognizer?.let { recognizer ->
            runCatching {
                recognizer.cancel()
                recognizer.destroy()
            }
        }
        speechRecognizer = null
        isListening = false
    }

    /**
     * 将系统 [SpeechRecognizer] 错误码翻译为可读中文消息。
     */
    private fun mapRecognitionError(errorCode: Int): String = when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO -> "录音音频录制失败，请检查麦克风是否被其他应用占用。"
        SpeechRecognizer.ERROR_CLIENT -> "系统语音识别客户端内部错误，请重试。"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "录音权限不足，请授予 RECORD_AUDIO 权限后重试。"
        SpeechRecognizer.ERROR_NETWORK -> "语音识别网络异常，请检查网络连接后重试。"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络超时，请稍后重试。"
        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到有效语音，请靠近麦克风或换一种说法。"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别器正忙，请稍后重试。"
        SpeechRecognizer.ERROR_SERVER -> "语音识别服务端错误，请稍后重试。"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音输入，请尝试重新开始。"
        else -> "未知语音识别错误（code=$errorCode）。"
    }

    /**
     * 系统识别器回调监听器：将原始 [RecognitionListener] 事件转换为 [VoiceInputResult] 回调。
     */
    private inner class VoiceRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // 系统准备就绪，无需额外动作；调用方可通过 isListening 状态自行判断。
        }

        override fun onBeginningOfSpeech() {
            // 检测到语音开始，预留扩展点；当前不上抛以避免回调过多。
        }

        override fun onRmsChanged(rmsdB: Float) {
            // 实时音量变化，暂不上抛；如需 UI 波形可在此扩展。
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // 原始音频缓冲不上抛，避免在回调链中持有大数组造成额外内存压力。
        }

        override fun onEndOfSpeech() {
            // 语音输入结束，等待 onResults/onError 回调。
        }

        override fun onError(error: Int) {
            isListening = false
            onResult(
                VoiceInputResult.Error(
                    code = error,
                    message = mapRecognitionError(error),
                ),
            )
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val text = extractRecognitionText(results)
            if (text.isNotBlank()) {
                onResult(VoiceInputResult.Final(text))
            } else {
                // 空结果按无匹配处理，避免静默失败让调用方无感知。
                onResult(
                    VoiceInputResult.Error(
                        code = SpeechRecognizer.ERROR_NO_MATCH,
                        message = mapRecognitionError(SpeechRecognizer.ERROR_NO_MATCH),
                    ),
                )
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = extractRecognitionText(partialResults)
            if (text.isNotBlank()) {
                onResult(VoiceInputResult.Partial(text))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // 预留系统事件扩展点，当前不处理。
        }

        /**
         * 从识别结果 Bundle 中取出最高置信度的第一条文本。
         */
        private fun extractRecognitionText(bundle: Bundle?): String {
            val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            return matches?.firstOrNull().orEmpty()
        }
    }
}
