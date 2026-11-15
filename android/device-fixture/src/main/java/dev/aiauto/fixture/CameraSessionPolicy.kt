package dev.aiauto.fixture

/** 功能用途：管理测试相机的有限重连代次与退避预算，成功重开不重置预算，暂停时取消旧回调。 */
class CameraSessionPolicy {
    enum class Phase { STOPPED, READY, OPENING, STREAMING, WAITING, EXHAUSTED }
    data class Retry(val generation: Int, val attempt: Int, val delayMs: Long)
    @Volatile var generation = 0
        private set
    @Volatile var phase = Phase.STOPPED
        private set
    var retries = 0
        private set
    var lastError: String? = null
        private set

    fun resume() { generation++; retries = 0; lastError = null; phase = Phase.READY }
    fun stop() { generation++; phase = Phase.STOPPED }
    fun isCurrent(token: Int) = token == generation && phase != Phase.STOPPED
    fun beginOpen(): Int? {
        if (phase != Phase.READY) return null
        phase = Phase.OPENING
        return generation
    }
    fun opened(token: Int): Boolean {
        if (token != generation || phase != Phase.OPENING) return false
        phase = Phase.STREAMING
        return true
    }
    fun failed(token: Int, reason: String): Retry? {
        if (token != generation || phase !in setOf(Phase.OPENING, Phase.STREAMING)) return null
        lastError = reason.replace('\n', ' ').replace('\r', ' ').take(256)
        generation++
        if (retries == 3) { phase = Phase.EXHAUSTED; return null }
        retries++
        phase = Phase.WAITING
        return Retry(generation, retries, 500L * (1L shl (retries - 1)))
    }
    fun retryReady(token: Int): Boolean {
        if (token != generation || phase != Phase.WAITING) return false
        phase = Phase.READY
        return true
    }
}

/** 功能用途：提供可单测的4:3预览尺寸，完整容纳640×480画面，不裁剪或拉伸。 */
object CameraPreviewSize {
    fun fit(width: Int, height: Int): Pair<Int, Int> {
        val unit = minOf(width / 4, height / 3).coerceAtLeast(0)
        return unit * 4 to unit * 3
    }
}
