package dev.aiauto.android.bridge

// 功能用途：实现 BridgeDispatcher 对应的桌面端与 App 本地 Bridge 协议、认证或请求处理。

import java.nio.charset.StandardCharsets

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BridgeDispatcher(
    private val methodHandler: BridgeMethodHandler,
    private val sessionManager: BridgeSessionManager,
    private val replayCache: RequestReplayCache = RequestReplayCache(),
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    },
) {
    fun dispatch(rawMessage: String, connection: BridgeConnectionState): String {
        if (
            rawMessage.toByteArray(StandardCharsets.UTF_8).size + 1 >
            BridgeLimits.MAX_MESSAGE_BYTES
        ) {
            return failureResponse(
                rawMessage,
                BridgeException(
                    code = BridgeErrorCode.MESSAGE_TOO_LARGE,
                    message = "The NDJSON message exceeds the 1048576 byte limit.",
                ),
            )
        }

        val request = try {
            parseRequest(rawMessage)
        } catch (error: BridgeException) {
            return failureResponse(rawMessage, error)
        } catch (_: Exception) {
            return failureResponse(
                rawMessage,
                BridgeException(
                    code = BridgeErrorCode.PROTOCOL_ERROR,
                    message = "The request is not valid JSON-RPC.",
                ),
            )
        }

        val response = try {
            val result = route(request, connection)
            successResponse(request, result)
        } catch (error: BridgeException) {
            failureResponse(request, error)
        } catch (_: Exception) {
            failureResponse(
                request,
                BridgeException(
                    code = BridgeErrorCode.INTERNAL_ERROR,
                    message = "The bridge could not complete the request.",
                ),
            )
        }

        if (
            response.toByteArray(StandardCharsets.UTF_8).size + 1 >
            BridgeLimits.MAX_MESSAGE_BYTES
        ) {
            return failureResponse(
                request,
                BridgeException(
                    code = BridgeErrorCode.MESSAGE_TOO_LARGE,
                    message = "The bridge response exceeds the 1048576 byte limit.",
                ),
            )
        }
        return response
    }

    fun deadlineMs(rawMessage: String): Int =
        runCatching {
            val root = json.parseToJsonElement(rawMessage) as JsonObject
            root["deadlineMs"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
            ?.takeIf { it in 1..BridgeLimits.MAX_DEADLINE_MS }
            ?: BridgeLimits.DEFAULT_DEADLINE_MS

    fun failureResponse(rawMessage: String, error: BridgeException): String {
        val metadata = parseMetadata(rawMessage)
        return failureResponse(
            id = metadata.id,
            requestId = metadata.requestId,
            protocolVersion = metadata.protocolVersion,
            error = error,
        )
    }

    fun genericFailure(error: BridgeException): String = failureResponse(
        id = BridgeProtocol.ZERO_UUID,
        requestId = BridgeProtocol.ZERO_UUID,
        protocolVersion = BridgeProtocol.VERSION,
        error = error,
    )

    private fun route(
        request: ParsedBridgeRequest,
        connection: BridgeConnectionState,
    ): JsonObject {
        if (!connection.helloCompleted && request.method != METHOD_HELLO) {
            throw BridgeException(
                code = BridgeErrorCode.PROTOCOL_ERROR,
                message = "rpc.hello must be the first message on each connection.",
            )
        }

        if (request.method == METHOD_HELLO) {
            if (connection.helloCompleted) {
                throw BridgeException(
                    code = BridgeErrorCode.PROTOCOL_ERROR,
                    message = "rpc.hello was already completed on this connection.",
                )
            }
            replayCache.record(request.requestId)
            return handleHello(request, connection)
        }

        if (request.protocolVersion != connection.protocolVersion) {
            throw BridgeException(
                code = BridgeErrorCode.VERSION_INCOMPATIBLE,
                message = "The request protocol version does not match the negotiated version.",
            )
        }

        if (request.method == METHOD_SESSION_OPEN) {
            replayCache.record(request.requestId)
            return handleSessionOpen(request.params)
        }

        replayCache.record(request.requestId)
        sessionManager.authenticate(request.token)
        return when (request.method) {
            METHOD_SESSION_CLOSE -> {
                requireExactKeys(request.params, emptySet())
                sessionManager.close(request.token)
                buildJsonObject { put("closed", true) }
            }

            METHOD_DEVICE_INFO,
            METHOD_UI_SNAPSHOT,
            METHOD_ACTION_EXECUTE,
            METHOD_RECORDING_LIST,
            METHOD_RECORDING_REPLAY,
            -> methodHandler.handle(request.method, request.params)

            else -> throw BridgeException(
                code = BridgeErrorCode.PROTOCOL_ERROR,
                message = "The requested bridge method is not supported.",
            )
        }
    }

    private fun handleHello(
        request: ParsedBridgeRequest,
        connection: BridgeConnectionState,
    ): JsonObject {
        val params = request.params
        requireExactKeys(
            params,
            setOf("clientVersion", "supportedProtocolVersions", "capabilities"),
        )
        val clientVersion = params.string("clientVersion")
        if (!SEMANTIC_VERSION.matches(clientVersion)) {
            throw invalidArgument("clientVersion is not a semantic version.")
        }
        val supported = params["supportedProtocolVersions"] as? JsonArray
            ?: throw invalidArgument("supportedProtocolVersions must be an array.")
        if (supported.isEmpty() || supported.size > 16) {
            throw invalidArgument("supportedProtocolVersions must contain between 1 and 16 items.")
        }
        val versions = supported.map { element ->
            (element as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.takeIf(PROTOCOL_VERSION::matches)
                ?: throw invalidArgument("supportedProtocolVersions contains an invalid version.")
        }
        if (versions.toSet().size != versions.size) {
            throw invalidArgument("supportedProtocolVersions must contain unique versions.")
        }
        val capabilities = params["capabilities"] as? JsonArray
        if (capabilities == null || capabilities.size > 128) {
            throw invalidArgument("capabilities must be an array.")
        }
        if (BridgeProtocol.VERSION !in versions) {
            throw BridgeException(
                code = BridgeErrorCode.VERSION_INCOMPATIBLE,
                message = "No compatible bridge protocol version is available.",
            )
        }

        connection.helloCompleted = true
        connection.protocolVersion = BridgeProtocol.VERSION
        return buildJsonObject {
            put("serverVersion", BridgeProtocol.SERVER_VERSION)
            put("selectedProtocolVersion", BridgeProtocol.VERSION)
            put("capabilities", capabilitiesJson())
        }
    }

    private fun handleSessionOpen(params: JsonObject): JsonObject {
        requireExactKeys(params, setOf("pairingCode", "hostName"))
        val code = params.string("pairingCode")
        if (!PAIRING_CODE.matches(code)) {
            throw invalidArgument("pairingCode must contain exactly six digits.")
        }
        val hostName = params.string("hostName")
        if (hostName.length !in 1..128) {
            throw invalidArgument("hostName must contain between 1 and 128 characters.")
        }
        val session = sessionManager.open(code = code, hostName = hostName)
        return buildJsonObject {
            put("token", session.token)
            put("expiresAt", session.expiresAt.toString())
            put("protocolVersion", BridgeProtocol.VERSION)
        }
    }

    private fun capabilitiesJson(): JsonArray = buildJsonArray {
        methodHandler.capabilities().forEach { capability ->
            add(
                buildJsonObject {
                    put("name", capability.name)
                    put("version", BridgeProtocol.SERVER_VERSION)
                    put("available", capability.available)
                    put("permission", capability.permission)
                    capability.reason?.let { put("reason", it) }
                    if (capability.limits.isNotEmpty()) {
                        put(
                            "limits",
                            buildJsonObject {
                                capability.limits.forEach { (name, value) ->
                                    put(name, value)
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun parseRequest(rawMessage: String): ParsedBridgeRequest {
        val root = try {
            json.parseToJsonElement(rawMessage) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw BridgeException(
            code = BridgeErrorCode.PROTOCOL_ERROR,
            message = "The request must be one JSON object.",
        )
        requireAllowedKeys(
            root,
            required = setOf(
                "jsonrpc",
                "id",
                "requestId",
                "protocolVersion",
                "method",
                "params",
                "deadlineMs",
            ),
            optional = setOf("token"),
        )
        if (root.string("jsonrpc") != BridgeProtocol.JSON_RPC_VERSION) {
            throw BridgeException(
                code = BridgeErrorCode.PROTOCOL_ERROR,
                message = "jsonrpc must be 2.0.",
            )
        }
        val id = root.string("id")
        val requestId = root.string("requestId")
        if (!UUID.matches(id) || !UUID.matches(requestId)) {
            throw invalidArgument("id and requestId must be UUID values.")
        }
        val protocolVersion = root.string("protocolVersion")
        if (!PROTOCOL_VERSION.matches(protocolVersion)) {
            throw invalidArgument("protocolVersion must use MAJOR.MINOR format.")
        }
        val deadlineMs = root["deadlineMs"]?.jsonPrimitive?.intOrNull
            ?: throw invalidArgument("deadlineMs must be an integer.")
        if (deadlineMs !in 1..BridgeLimits.MAX_DEADLINE_MS) {
            throw invalidArgument("deadlineMs must be between 1 and 300000.")
        }
        val method = root.string("method")
        val params = root["params"] as? JsonObject
            ?: throw invalidArgument("params must be an object.")
        val token = root["token"]?.let { element ->
            (element as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?: throw invalidArgument("token must be a string.")
        }
        return ParsedBridgeRequest(
            id = id,
            requestId = requestId,
            protocolVersion = protocolVersion,
            method = method,
            params = params,
            deadlineMs = deadlineMs,
            token = token,
        )
    }

    private fun successResponse(
        request: ParsedBridgeRequest,
        result: JsonObject,
    ): String = buildJsonObject {
        put("jsonrpc", BridgeProtocol.JSON_RPC_VERSION)
        put("id", request.id)
        put("requestId", request.requestId)
        put("protocolVersion", BridgeProtocol.VERSION)
        put("result", result)
    }.toString()

    private fun failureResponse(
        request: ParsedBridgeRequest,
        error: BridgeException,
    ): String = failureResponse(
        id = request.id,
        requestId = request.requestId,
        protocolVersion = BridgeProtocol.VERSION,
        error = error,
    )

    private fun failureResponse(
        id: String,
        requestId: String,
        protocolVersion: String,
        error: BridgeException,
    ): String = buildJsonObject {
        put("jsonrpc", BridgeProtocol.JSON_RPC_VERSION)
        put("id", id)
        put("requestId", requestId)
        put("protocolVersion", protocolVersion)
        put(
            "error",
            buildJsonObject {
                put("code", error.code.rpcCode)
                put("message", error.message)
                put(
                    "data",
                    buildJsonObject {
                        put("code", error.code.wireCode)
                        put("message", error.message)
                        put("retryable", error.retryable)
                        if (error.details.isNotEmpty()) {
                            put(
                                "details",
                                buildJsonObject {
                                    error.details.forEach { (name, value) ->
                                        put(name, value)
                                    }
                                },
                            )
                        }
                    },
                )
            },
        )
    }.toString()

    private fun parseMetadata(rawMessage: String): ResponseMetadata =
        runCatching {
            val root = json.parseToJsonElement(rawMessage) as JsonObject
            ResponseMetadata(
                id = root.stringOrNull("id")?.takeIf(UUID::matches)
                    ?: BridgeProtocol.ZERO_UUID,
                requestId = root.stringOrNull("requestId")?.takeIf(UUID::matches)
                    ?: BridgeProtocol.ZERO_UUID,
                protocolVersion = root.stringOrNull("protocolVersion")
                    ?.takeIf(PROTOCOL_VERSION::matches)
                    ?: BridgeProtocol.VERSION,
            )
        }.getOrElse {
            ResponseMetadata(
                id = BridgeProtocol.ZERO_UUID,
                requestId = BridgeProtocol.ZERO_UUID,
                protocolVersion = BridgeProtocol.VERSION,
            )
        }

    private fun requireExactKeys(value: JsonObject, required: Set<String>) {
        requireAllowedKeys(value, required, emptySet())
    }

    private fun requireAllowedKeys(
        value: JsonObject,
        required: Set<String>,
        optional: Set<String>,
    ) {
        val missing = required - value.keys
        val extra = value.keys - required - optional
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            throw invalidArgument("Method parameters contain missing or unsupported fields.")
        }
    }

    private fun JsonObject.string(name: String): String =
        stringOrNull(name) ?: throw invalidArgument("$name must be a string.")

    private fun JsonObject.stringOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content

    private fun invalidArgument(message: String): BridgeException =
        BridgeException(
            code = BridgeErrorCode.INVALID_ARGUMENT,
            message = message,
        )

    private data class ResponseMetadata(
        val id: String,
        val requestId: String,
        val protocolVersion: String,
    )

    private data class ParsedBridgeRequest(
        val id: String,
        val requestId: String,
        val protocolVersion: String,
        val method: String,
        val params: JsonObject,
        val deadlineMs: Int,
        val token: String?,
    )

    private companion object {
        const val METHOD_HELLO = "rpc.hello"
        const val METHOD_SESSION_OPEN = "session.open"
        const val METHOD_SESSION_CLOSE = "session.close"
        const val METHOD_DEVICE_INFO = "device.info"
        const val METHOD_UI_SNAPSHOT = "ui.snapshot"
        const val METHOD_ACTION_EXECUTE = "action.execute"
        const val METHOD_RECORDING_LIST = "recording.list"
        const val METHOD_RECORDING_REPLAY = "recording.replay"

        val UUID = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )
        val PROTOCOL_VERSION = Regex("^[1-9][0-9]*\\.[0-9]+$")
        val SEMANTIC_VERSION = Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$",
        )
        val PAIRING_CODE = Regex("^[0-9]{6}$")
    }
}
