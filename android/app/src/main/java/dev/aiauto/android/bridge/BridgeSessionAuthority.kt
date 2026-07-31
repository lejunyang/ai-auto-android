package dev.aiauto.android.bridge

/**
 * 功能用途：为 BridgeDispatcher 提供最小会话认证面，使 LAN 使用独立凭据而不接触 loopback 状态。
 */

interface BridgeSessionAuthority {
    val transportAuthenticated: Boolean
        get() = false

    fun open(code: String, hostName: String): OpenedBridgeSession

    fun authenticate(token: String?)

    fun close(token: String?)
}
