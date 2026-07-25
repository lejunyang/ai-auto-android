package dev.aiauto.android.bridge.lan

/**
 * 测试用途：逐项消费共享邀请夹具，验证全部协议威胁在建立网络连接前失败关闭，并验证
 * 严格字段、版本兼容和敏感字节生命周期。
 */

import java.io.File
import java.time.Instant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanInvitationContractTest {
    private val validPayload = fixture("lan-invitation-v1-valid.json")
    private val validObject = Json.parseToJsonElement(validPayload).jsonObject
    private val threatCases = Json.parseToJsonElement(
        fixture("lan-invitation-v1-threats.json"),
    ).jsonObject.getValue("cases").jsonArray

    @Test
    fun `valid N36 invitation passes strict parse and preflight`() {
        val invitation = LanInvitationParser.parse(validPayload)
        val result = LanInvitationPreflight.validate(
            invitation = invitation,
            context = LanPreflightContext(now = Instant.parse("2026-07-25T10:00:30Z")),
        )

        assertTrue(result.accepted)
        assertEquals(null, result.code)
        assertEquals("8975-0256-F8CF-C56A", invitation.fingerprint)
        assertEquals(4, invitation.addressCandidates.size)
    }

    @Test
    fun `all schema threat fixtures are rejected with the declared code`() {
        casesAtStage("schema").forEach { case ->
            val name = case.string("name")
            val mutated = applyMutations(validObject, case.getValue("mutations").jsonArray)

            assertProtocolFailure(name, case.string("expectedCode")) {
                LanInvitationParser.parse(mutated.toString())
            }
        }
    }

    @Test
    fun `all semantic threat fixtures are rejected before socket creation`() {
        casesAtStage("preflight").forEach { case ->
            val name = case.string("name")
            val mutated = applyMutations(validObject, case.getValue("mutations").jsonArray)
            val invitation = LanInvitationParser.parse(mutated.toString())
            val socket = CountingSocketAttempt()
            val context = case["context"]?.jsonObject
            val result = LanInvitationPreflight.validate(
                invitation = invitation,
                context = LanPreflightContext(
                    now = Instant.parse(context?.string("now") ?: "2026-07-25T10:00:30Z"),
                    consumedInvitationIds = context?.strings("consumedInvitationIds").orEmpty(),
                    consumedNonces = context?.strings("consumedNonces").orEmpty(),
                    expectedDesktopInterface = context?.get("expectedInterface")?.jsonObject?.let {
                        LanInterface(
                            id = it.string("id"),
                            name = it.string("name"),
                            kind = it.string("kind"),
                        )
                    },
                ),
            )
            if (result.accepted) {
                socket.connect()
            }

            assertFalse(name, result.accepted)
            assertEquals(name, case.string("expectedCode"), result.code)
            assertEquals("$name reached the socket", 0, socket.attempts)
        }
    }

    @Test
    fun `fixture suite contains exactly all eighteen N36 threats`() {
        assertEquals(18, threatCases.size)
        assertEquals(6, casesAtStage("schema").size)
        assertEquals(12, casesAtStage("preflight").size)
    }

    @Test
    fun `UUID version seven is accepted by the N36 common schema contract`() {
        val uuidV7 = JsonObject(
            validObject.toMutableMap().apply {
                put("invitationId", JsonPrimitive("018f4f2a-7b3c-7def-8abc-0123456789ab"))
            },
        )
        val withFingerprint = JsonObject(
            uuidV7.toMutableMap().apply {
                put(
                    "fingerprint",
                    JsonPrimitive(LanCrypto.invitationFingerprint(uuidV7)),
                )
            },
        )
        LanInvitationParser.parse(withFingerprint.toString()).use { invitation ->
            assertEquals("018f4f2a-7b3c-7def-8abc-0123456789ab", invitation.invitationId)
        }
    }

    @Test
    fun `failed parse clears every sensitive byte allocated before the failure`() {
        val observer = RecordingSecretObserver()
        val invalid = JsonObject(
            validObject.toMutableMap().apply {
                put("fingerprint", JsonPrimitive("INVALID"))
            },
        )

        try {
            LanInvitationParser.parse(invalid.toString(), observer)
            fail("invalid fingerprint should fail")
        } catch (error: LanProtocolException) {
            assertEquals("LAN_INVITATION_SCHEMA_INVALID", error.code)
        }

        assertEquals(2, observer.allocated.size)
        observer.allocated.forEach { secret ->
            assertTrue(secret.all { it.toInt() == 0 })
        }
    }

    @Test
    fun `invitation close clears nonce public key and prevents later use`() {
        val invitation = LanInvitationParser.parse(validPayload)
        val nonce = invitation.secretBytesForTest().first
        val publicKey = invitation.secretBytesForTest().second

        invitation.close()

        assertTrue(nonce.all { it.toInt() == 0 })
        assertTrue(publicKey.all { it.toInt() == 0 })
        assertTrue(invitation.destroyed)
        try {
            invitation.nonceBase64Url()
            fail("destroyed invitation must not expose nonce")
        } catch (error: LanProtocolException) {
            assertEquals("LAN_INVITATION_DESTROYED", error.code)
        }
    }

    private fun casesAtStage(stage: String): List<JsonObject> = threatCases
        .map(JsonElement::jsonObject)
        .filter { it.string("stage") == stage }

    private fun assertProtocolFailure(
        name: String,
        expectedCode: String,
        action: () -> Unit,
    ) {
        try {
            action()
            fail("$name should fail")
        } catch (error: LanProtocolException) {
            assertEquals(name, expectedCode, error.code)
        }
    }

    private class CountingSocketAttempt {
        var attempts: Int = 0
            private set

        fun connect() {
            attempts += 1
        }
    }

    private class RecordingSecretObserver : LanInvitationSecretObserver {
        val allocated = mutableListOf<ByteArray>()

        override fun onAllocated(secret: ByteArray) {
            allocated += secret
        }
    }

    private companion object {
        fun JsonObject.string(name: String): String =
            getValue(name).jsonPrimitive.content

        fun JsonObject.strings(name: String): Set<String> =
            (get(name) as? JsonArray)
                ?.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
                .orEmpty()

        fun applyMutations(base: JsonObject, mutations: JsonArray): JsonObject {
            var current: JsonElement = base
            mutations.forEach { element ->
                val mutation = element.jsonObject
                current = mutate(
                    current = current,
                    parts = mutation.string("path")
                        .removePrefix("/")
                        .split("/")
                        .map { it.replace("~1", "/").replace("~0", "~") },
                    operation = mutation.string("op"),
                    value = mutation["value"],
                )
            }
            return current.jsonObject
        }

        fun mutate(
            current: JsonElement,
            parts: List<String>,
            operation: String,
            value: JsonElement?,
        ): JsonElement {
            val head = parts.first()
            if (current is JsonObject) {
                val copy = current.toMutableMap()
                if (parts.size == 1) {
                    if (operation == "remove") copy.remove(head) else copy[head] = requireNotNull(value)
                } else {
                    copy[head] = mutate(
                        current = current.getValue(head),
                        parts = parts.drop(1),
                        operation = operation,
                        value = value,
                    )
                }
                return JsonObject(copy)
            }
            val array = current.jsonArray.toMutableList()
            val index = head.toInt()
            array[index] = if (parts.size == 1) {
                requireNotNull(value)
            } else {
                mutate(array[index], parts.drop(1), operation, value)
            }
            return JsonArray(array)
        }

        fun fixture(name: String): String {
            val start = requireNotNull(System.getProperty("user.dir")) {
                "user.dir is required to locate protocol fixtures"
            }.let(::File)
            val file = generateSequence(start, File::getParentFile)
                .map { directory -> File(directory, "protocol/fixtures/$name") }
                .firstOrNull(File::isFile)
            return requireNotNull(file) {
                "Unable to locate protocol fixture $name from ${start.absolutePath}"
            }.readText()
        }
    }
}
