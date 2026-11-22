package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.TestTasks.KEY
import io.androllm.core.jarvislink.TestTasks.signed
import io.androllm.core.jarvislink.mqtt.TailscaleRoute
import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.ResultStatus
import io.androllm.core.jarvislink.protocol.TaskParse
import java.net.InetAddress
import org.junit.Test

class JarvisProtocolTest {

    private val now = 1_800_000_000_000L
    private fun parse(bytes: ByteArray, key: ByteArray? = KEY) = JarvisProtocol.parseTask(bytes, now, key)

    @Test
    fun `valid signed task is parsed`() {
        val r = parse(signed("abc-1", "  какая погода?  ", ts = now))
        val t = (r as TaskParse.Valid).task
        assertThat(t.id).isEqualTo("abc-1")
        assertThat(t.text).isEqualTo("какая погода?")
        assertThat(t.sentAtMs).isEqualTo(now)
        assertThat(t.ttlSeconds).isEqualTo(600)
    }

    @Test
    fun `unsigned, wrongly signed and tampered tasks are rejected without an answerable id`() {
        listOf(
            signed("t1", ts = now, omitSig = true),
            signed("t2", ts = now, key = TestTasks.OTHER_KEY),
            signed("t3", "read weather", ts = now, tamperText = "send sms to everyone")
        ).forEach {
            val r = parse(it)
            assertThat(r).isInstanceOf(TaskParse.Invalid::class.java)
            assertThat((r as TaskParse.Invalid).id).isNull() // forgeries are never answered
        }
    }

    @Test
    fun `nothing is accepted without a configured key`() {
        assertThat(parse(signed("t", ts = now), key = null)).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse(signed("t", ts = now), key = ByteArray(8))).isInstanceOf(TaskParse.Invalid::class.java)
    }

    @Test
    fun `ts is mandatory`() {
        val r = parse(signed("t", ts = now, omitTs = true)) as TaskParse.Invalid
        assertThat(r.reason).contains("ts")
    }

    @Test
    fun `future ts beyond skew is rejected, small skew accepted`() {
        assertThat(parse(signed("f1", ts = now + JarvisProtocol.CLOCK_SKEW_MS + 1_000))).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse(signed("f2", ts = now + 30_000))).isInstanceOf(TaskParse.Valid::class.java)
    }

    @Test
    fun `stale task beyond ttl plus skew is expired`() {
        val r = parse(signed("old", ts = now - 60_000 - JarvisProtocol.CLOCK_SKEW_MS - 1, ttl = 60))
        assertThat(r).isInstanceOf(TaskParse.Expired::class.java)
    }

    @Test
    fun `ttl outside 1 to max is invalid`() {
        assertThat(parse(signed("a", ts = now, ttl = JarvisProtocol.MAX_TTL_S + 1))).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse(signed("b", ts = now, ttl = 0))).isInstanceOf(TaskParse.Invalid::class.java)
    }

    @Test
    fun `non json, missing id and malformed id are invalid`() {
        listOf("not json", "[1]", "{}").forEach { assertThat(parse(it.toByteArray())).isInstanceOf(TaskParse.Invalid::class.java) }
        assertThat(parse(signed("has space", ts = now))).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse(signed("x".repeat(65), ts = now))).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse(signed("jarvis.task:42-a_b", ts = now))).isInstanceOf(TaskParse.Valid::class.java)
    }

    @Test
    fun `authentic but blank or oversized text is invalid and answerable`() {
        val blank = parse(signed("t2", "   ", ts = now)) as TaskParse.Invalid
        assertThat(blank.id).isEqualTo("t2")
        val long = parse(signed("t3", "a".repeat(JarvisProtocol.MAX_TEXT_LENGTH + 1), ts = now)) as TaskParse.Invalid
        assertThat(long.id).isEqualTo("t3")
    }

    @Test
    fun `payload over the size cap is rejected`() {
        val big = ByteArray(JarvisProtocol.MAX_PAYLOAD_BYTES + 1) { 'a'.code.toByte() }
        assertThat(parse(big)).isInstanceOf(TaskParse.Invalid::class.java)
    }

    @Test
    fun `key hex parsing`() {
        assertThat(JarvisProtocol.parseKeyHex(TestTasks.KEY_HEX)).hasLength(32)
        assertThat(JarvisProtocol.parseKeyHex("abc")).isNull()
        assertThat(JarvisProtocol.parseKeyHex("zz".repeat(32))).isNull()
    }

    @Test
    fun `result is signed, verifiable and round trips unicode`() {
        val bytes = JarvisProtocol.encodeResult("t1", ResultStatus.OK, "Готово: \"ok\"\nline2", now, KEY)
        val back = JarvisProtocol.decodeResult(bytes)
        assertThat(back.text).isEqualTo("Готово: \"ok\"\nline2")
        assertThat(back.status).isEqualTo(ResultStatus.OK)
        assertThat(JarvisProtocol.verifyResult(back, KEY)).isTrue()
        assertThat(JarvisProtocol.verifyResult(back.copy(text = "forged"), KEY)).isFalse()
        assertThat(JarvisProtocol.verifyResult(back, TestTasks.OTHER_KEY)).isFalse()
    }

    @Test
    fun `huge result text is truncated`() {
        val back = JarvisProtocol.decodeResult(
            JarvisProtocol.encodeResult("t", ResultStatus.OK, "x".repeat(JarvisProtocol.MAX_RESULT_TEXT + 10), now, KEY)
        )
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
