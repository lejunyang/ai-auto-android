package dev.aiauto.android.bridge.lan

/**
 * 功能用途：以 OS 文件锁和原子替换持久化 invitation/nonce 摘要，防止并发和进程恢复重放。
 */

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class FileLanReplayStore(
    private val stateFile: File,
) : LanReplayStore {
    private val lockFile = File(stateFile.parentFile, "${stateFile.name}.lock")
    private val processLock = LOCKS.computeIfAbsent(stateFile.absoluteFile.normalize().path) {
        Any()
    }

    override fun claim(key: LanReplayKey, now: Instant): LanReplayClaim =
        synchronized(processLock) {
            requireDigest(key.invitationIdDigest)
            requireDigest(key.nonceDigest)
            if (key.expiresAt <= now) {
                throw LanProtocolException("LAN_INVITATION_EXPIRED")
            }
            stateFile.parentFile?.mkdirs()
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use {
                    val claims = readClaims()
                        .filter { claim -> claim.expiresAt > now }
                        .toMutableList()
                    when {
                        claims.any { it.invitationIdDigest == key.invitationIdDigest } ->
                            LanReplayClaim.INVITATION_REPLAYED
                        claims.any { it.nonceDigest == key.nonceDigest } ->
                            LanReplayClaim.NONCE_REPLAYED
                        else -> {
                            claims += key
                            writeClaims(claims)
                            LanReplayClaim.CLAIMED
                        }
                    }
                }
            }
        }

    private fun readClaims(): List<LanReplayKey> {
        if (!stateFile.isFile) return emptyList()
        return try {
            val root = JSON.parseToJsonElement(stateFile.readText()).jsonObject
            if (root.keys != setOf("version", "claims") || root.string("version") != "1.0") {
                throw LanProtocolException("LAN_REPLAY_STATE_INVALID")
            }
            root.getValue("claims").jsonArray.map { element ->
                val claim = element.jsonObject
                if (claim.keys != setOf("invitationIdDigest", "nonceDigest", "expiresAt")) {
                    throw LanProtocolException("LAN_REPLAY_STATE_INVALID")
                }
                LanReplayKey(
                    invitationIdDigest = claim.string("invitationIdDigest").also(::requireDigest),
                    nonceDigest = claim.string("nonceDigest").also(::requireDigest),
                    expiresAt = Instant.parse(claim.string("expiresAt")),
                )
            }
        } catch (error: LanProtocolException) {
            throw error
        } catch (error: Exception) {
            throw LanProtocolException(
                "LAN_REPLAY_STATE_INVALID",
                "LAN replay state cannot be trusted",
                error,
            )
        }
    }

    private fun writeClaims(claims: List<LanReplayKey>) {
        val temporary = File(stateFile.parentFile, "${stateFile.name}.tmp")
        try {
            temporary.writeText(
                buildJsonObject {
                    put("version", "1.0")
                    put(
                        "claims",
                        buildJsonArray {
                            claims.sortedBy(LanReplayKey::expiresAt).forEach { claim ->
                                add(
                                    buildJsonObject {
                                        put("invitationIdDigest", claim.invitationIdDigest)
                                        put("nonceDigest", claim.nonceDigest)
                                        put("expiresAt", claim.expiresAt.toString())
                                    },
                                )
                            }
                        },
                    )
                }.toString(),
            )
            FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use {
                channel: FileChannel ->
                channel.force(true)
            }
            try {
                Files.move(
                    temporary.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun requireDigest(value: String) {
        if (!DIGEST_PATTERN.matches(value)) {
            throw LanProtocolException("LAN_REPLAY_STATE_INVALID")
        }
    }

    private fun JsonObject.string(name: String): String {
        val primitive = getValue(name).jsonPrimitive
        if (!primitive.isString) throw LanProtocolException("LAN_REPLAY_STATE_INVALID")
        return primitive.content
    }

    private companion object {
        val JSON = Json { isLenient = false }
        val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
        val LOCKS = ConcurrentHashMap<String, Any>()
    }
}
