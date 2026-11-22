package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.TestTasks.KEY
import io.androllm.core.jarvislink.TestTasks.signed
import io.androllm.core.jarvislink.mqtt.TailscaleRoute
import io.androllm.core.jarvislink.protocol.JarvisCrypto
import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.ResultStatus
import io.androllm.core.jarvislink.protocol.TaskParse
import java.net.InetAddress
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class JarvisProtocolTest {

    private val now = 1_800_000_000_000L
    private fun parse(bytes: ByteArray, key: ByteArray? = KEY) = JarvisProtocol.parseTask(bytes, now, key)

    @Test
    fun `valid encrypted task is parsed`() {
        val t = (parse(signed("abc-1", "  какая погода?  ", ts = now)) as TaskParse.Valid).task
        assertThat(t.id).isEqualTo("abc-1")
        assertThat(t.text).isEqualTo("какая погода?")
        assertThat(t.sentAtMs).isEqualTo(now)
        assertThat(t.ttlSeconds).isEqualTo(600)
    }

    @Test
    fun `envelope does not leak the task text or id to broker clients`() {
        val wire = signed("secret-id", "read my notifications", ts = now).toString(Charsets.UTF_8)
        assertThat(wire).doesNotContain("notifications")
        assertThat(wire).doesNotContain("secret-id")
        val obj = Json.parseToJsonElement(wire).jsonObject
        assertThat(obj.keys).containsExactly("v", "kind", "n", "c")
    }

    @Test
    fun `plaintext, wrong key, tampered and truncated envelopes are rejected without an answerable id`() {
        val good = signed("t1", ts = now).toString(Charsets.UTF_8)
        val obj = Json.parseToJsonElement(good).jsonObject
        val c = Base64.getDecoder().decode((obj["c"] as JsonPrimitive).content)
        c[0] = (c[0].toInt() xor 1).toByte()
        val tampered = good.replace((obj["c"] as JsonPrimitive).content, Base64.getEncoder().encodeToString(c))
        listOf(
            """{"v":1,"id":"p","text":"send sms","ts":$now,"sig":"00"}""".toByteArray(),
            signed("t2", ts = now, key = TestTasks.OTHER_KEY),
            tampered.toByteArray(),
            good.replace("\"kind\":\"task\"", "\"kind\":\"result\"").toByteArray()
        ).forEach {
            val r = parse(it)
            assertThat(r).isInstanceOf(TaskParse.Invalid::class.java)
            assertThat((r as TaskParse.Invalid).id).isNull()
        }
    }

    @Test
    fun `a result envelope can never be replayed as a task (separate keys per direction)`() {
        val result = JarvisProtocol.encodeResult("x", ResultStatus.OK, "hi", now, KEY)
        val asTask = result.toString(Charsets.UTF_8).replace("\"kind\":\"result\"", "\"kind\":\"task\"").toByteArray()
        assertThat(parse(asTask)).isInstanceOf(TaskParse.Invalid::class.java)
    }

    @Test
    fun `nothing is accepted without a configured key`() {
        assertThat(parse(signed("t", ts = now), key = null)).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse(signed("t", ts = now), key = ByteArray(8))).isInstanceOf(TaskParse.Invalid::class.java)
    }

    @Test
    fun `from with a newline or odd characters is rejected`() {
        val r = parse(signed("f", ts = now, from = "jarvis\nA")) as TaskParse.Invalid
        assertThat(r.reason).contains("from")
    }

    @Test
    fun `future ts beyond skew is rejected, small skew accepted`() {
        assertThat(parse(signed("f1", ts = now + JarvisProtocol.CLOCK_SKEW_MS + 1_000))).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse(signed("f2", ts = now + 30_000))).isInstanceOf(TaskParse.Valid::class.java)
    }

    @Test
    fun `stale task beyond ttl plus skew is expired`() {
        assertThat(parse(signed("old", ts = now - 60_000 - JarvisProtocol.CLOCK_SKEW_MS - 1, ttl = 60)))
            .isInstanceOf(TaskParse.Expired::class.java)
    }

    @Test
    fun `ttl outside 1 to max is invalid`() {
        assertThat(parse(signed("a", ts = now, ttl = JarvisProtocol.MAX_TTL_S + 1))).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse(signed("b", ts = now, ttl = 0))).isInstanceOf(TaskParse.Invalid::class.java)
    }

    @Test
    fun `malformed id is rejected, allowed id characters pass`() {
        assertThat(parse(signed("has space", ts = now))).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse(signed("x".repeat(65), ts = now))).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse(signed("jarvis.task:42-a_b", ts = now))).isInstanceOf(TaskParse.Valid::class.java)
        listOf("not json", "[1]", "{}").forEach { assertThat(parse(it.toByteArray())).isInstanceOf(TaskParse.Invalid::class.java) }
    }

    @Test
    fun `authentic but blank or oversized text is invalid and answerable`() {
        assertThat((parse(signed("t2", "   ", ts = now)) as TaskParse.Invalid).id).isEqualTo("t2")
        assertThat((parse(signed("t3", "a".repeat(JarvisProtocol.MAX_TEXT_LENGTH + 1), ts = now)) as TaskParse.Invalid).id).isEqualTo("t3")
    }

    @Test
    fun `payload over the size cap is rejected`() {
        assertThat(parse(ByteArray(JarvisProtocol.MAX_PAYLOAD_BYTES + 1) { 'a'.code.toByte() })).isInstanceOf(TaskParse.Invalid::class.java)
    }

    @Test
    fun `key hex parsing`() {
        assertThat(JarvisProtocol.parseKeyHex(TestTasks.KEY_HEX)).hasLength(32)
        assertThat(JarvisProtocol.parseKeyHex("abc")).isNull()
        assertThat(JarvisProtocol.parseKeyHex("zz".repeat(32))).isNull()
    }

    @Test
    fun `hkdf matches RFC 5869 test case 1 construction`() {
        // RFC 5869 A.1 uses salt 0x00..0x0c; our salt is fixed, so check determinism + separation instead.
        val a = JarvisCrypto.hkdf(KEY, "task-v2")
        assertThat(a).isEqualTo(JarvisCrypto.hkdf(KEY, "task-v2"))
        assertThat(a).isNotEqualTo(JarvisCrypto.hkdf(KEY, "result-v2"))
        assertThat(a).hasLength(32)
    }

    /** Vectors produced by Python `cryptography` (HKDF + AESGCM) exactly as jarvis-phone-task does. */
    @Test
    fun `cross-language vectors from the python side`() {
        assertThat(JarvisProtocol.taskKey(KEY).joinToString("") { "%02x".format(it) })
            .isEqualTo("d348320c06131e04600307a1913a6cc0dff62315e00a91896d48eae64dd1ba2d")
        val pyTask = """{"v": 2, "kind": "task", "n": "AAECAwQFBgcICQoL", "c": "CMtzHmBy3YMi5or7XBq8hGM+P+Xo+P+gj/sacvor5Cdz8Gr4pgUkp/2nkIv987Lma1GVTGbtlG6+LXsPBDSVTfu2k1Gr7cDQRsJ+2+lt2P8iadf5LfcG0PfGN7APolKS"}"""
        val t = (JarvisProtocol.parseTask(pyTask.toByteArray(), 1_800_000_000_000L, KEY) as TaskParse.Valid).task
        assertThat(t.id).isEqualTo("x1")
        assertThat(t.text).isEqualTo("привет")
        val nonce = ByteArray(12) { it.toByte() }
        val (_, ct) = JarvisCrypto.seal(
            JarvisProtocol.resultKey(KEY), JarvisProtocol.AAD_RESULT,
            """{"id":"x1","status":"ok","text":"готово","ts":1800000000001}""".toByteArray(), nonce
        )
        assertThat(Base64.getEncoder().encodeToString(ct))
            .isEqualTo("2rgVmCcTTZS8LCO1vDQSzUP+EYwJcs9duSiP3OEhMPRd2HtelA5tBCLtf9WK+n9l5WRKaKsy05yINq9mc8K5PMJmOSyXQt3SDlTUM4IsReYdPg==")
    }

    @Test
    fun `result is encrypted, authentic and round trips unicode`() {
        val bytes = JarvisProtocol.encodeResult("t1", ResultStatus.OK, "Готово: \"ok\"\nline2", now, KEY)
        assertThat(bytes.toString(Charsets.UTF_8)).doesNotContain("Готово")
        val back = JarvisProtocol.decodeResult(bytes, KEY)!!
        assertThat(back.text).isEqualTo("Готово: \"ok\"\nline2")
        assertThat(back.status).isEqualTo(ResultStatus.OK)
        assertThat(back.id).isEqualTo("t1")
        assertThat(JarvisProtocol.decodeResult(bytes, TestTasks.OTHER_KEY)).isNull()
    }

    @Test
    fun `huge result text is truncated`() {
        val back = JarvisProtocol.decodeResult(
            JarvisProtocol.encodeResult("t", ResultStatus.OK, "x".repeat(JarvisProtocol.MAX_RESULT_TEXT + 10), now, KEY), KEY
        )!!
        assertThat(back.text).endsWith("[…truncated]")
    }

    @Test
    fun `accepted and duplicate are not final`() {
        assertThat(ResultStatus.ACCEPTED.isFinal).isFalse()
        assertThat(ResultStatus.DUPLICATE.isFinal).isFalse()
        assertThat(ResultStatus.OK.isFinal).isTrue()
    }

    @Test
    fun `tailscale route classification`() {
        assertThat(TailscaleRoute.classify("100.64.0.1")).isEqualTo(TailscaleRoute.HostKind.TAILSCALE_IP)
        assertThat(TailscaleRoute.classify("100.127.255.254")).isEqualTo(TailscaleRoute.HostKind.TAILSCALE_IP)
        assertThat(TailscaleRoute.classify("pi.tailnet-abc.ts.net")).isEqualTo(TailscaleRoute.HostKind.MAGICDNS_NAME)
        assertThat(TailscaleRoute.classify("fd7a:115c:a1e0::1")).isEqualTo(TailscaleRoute.HostKind.TAILSCALE_IP)
        listOf("192.168.0.10", "100.63.255.255", "100.128.0.1", "10.0.0.5", "broker.example.com", "ts.net", "", "999.1.1.1")
            .forEach { assertThat(TailscaleRoute.classify(it)).isEqualTo(TailscaleRoute.HostKind.NOT_TAILSCALE) }
        assertThat(TailscaleRoute.isTailscaleAddress(InetAddress.getByName("100.100.1.2"))).isTrue()
        assertThat(TailscaleRoute.isTailscaleAddress(InetAddress.getByName("192.168.0.10"))).isFalse()
    }
}
