package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.ResultStatus
import io.androllm.core.jarvislink.protocol.TaskParse
import org.junit.Test

class JarvisProtocolTest {

    private val now = 1_800_000_000_000L
    private fun parse(json: String) = JarvisProtocol.parseTask(json.toByteArray(), now)

    @Test
    fun `valid task is parsed with defaults`() {
        val r = parse("""{"id":"abc-1","text":"  какая погода?  "}""")
        assertThat(r).isInstanceOf(TaskParse.Valid::class.java)
        val t = (r as TaskParse.Valid).task
        assertThat(t.id).isEqualTo("abc-1")
        assertThat(t.text).isEqualTo("какая погода?")
        assertThat(t.from).isEqualTo("jarvis")
        assertThat(t.ttlSeconds).isEqualTo(JarvisProtocol.DEFAULT_TTL_S)
        assertThat(t.sentAtMs).isNull()
    }

    @Test
    fun `full task with unknown fields is accepted`() {
        val r = parse("""{"v":1,"id":"t1","text":"hi","from":"jarvis","ts":$now,"ttl_s":60,"extra":{"x":1}}""")
        val t = (r as TaskParse.Valid).task
        assertThat(t.sentAtMs).isEqualTo(now)
        assertThat(t.ttlSeconds).isEqualTo(60)
    }

    @Test
    fun `non json and non object payloads are invalid without id`() {
        listOf("not json", "[1,2]", "\"str\"", "").forEach {
            val r = parse(it)
            assertThat(r).isInstanceOf(TaskParse.Invalid::class.java)
            assertThat((r as TaskParse.Invalid).id).isNull()
        }
    }

    @Test
    fun `missing or malformed id is invalid`() {
        assertThat(parse("""{"text":"hi"}""")).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse("""{"id":123,"text":"hi"}""")).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse("""{"id":"has space","text":"hi"}""")).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse("""{"id":"${"x".repeat(65)}","text":"hi"}""")).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat(parse("""{"id":"jarvis.task:42-a_b","text":"hi"}""")).isInstanceOf(TaskParse.Valid::class.java) // dots, colons, dashes allowed
    }

    @Test
    fun `blank or oversized text is invalid but answerable by id`() {
        val blank = parse("""{"id":"t2","text":"   "}""") as TaskParse.Invalid
        assertThat(blank.id).isEqualTo("t2")
        val long = parse("""{"id":"t3","text":"${"a".repeat(JarvisProtocol.MAX_TEXT_LENGTH + 1)}"}""") as TaskParse.Invalid
        assertThat(long.id).isEqualTo("t3")
        assertThat(parse("""{"id":"t4","text":42}""")).isInstanceOf(TaskParse.Invalid::class.java)
    }

    @Test
    fun `unsupported version is rejected`() {
        val r = parse("""{"v":2,"id":"t5","text":"hi"}""") as TaskParse.Invalid
        assertThat(r.reason).contains("version")
    }

    @Test
    fun `payload over the size cap is rejected before parsing`() {
        val big = ByteArray(JarvisProtocol.MAX_PAYLOAD_BYTES + 1) { 'a'.code.toByte() }
        assertThat(JarvisProtocol.parseTask(big, now)).isInstanceOf(TaskParse.Invalid::class.java)
    }

    @Test
    fun `stale task beyond ttl plus skew is expired`() {
        val ts = now - 60_000 - JarvisProtocol.CLOCK_SKEW_MS - 1
        val r = parse("""{"id":"old","text":"send sms","ts":$ts,"ttl_s":60}""")
        assertThat(r).isInstanceOf(TaskParse.Expired::class.java)
        assertThat((r as TaskParse.Expired).id).isEqualTo("old")
    }

    @Test
    fun `task within ttl or with small future skew is valid`() {
        assertThat(parse("""{"id":"a","text":"x","ts":${now - 59_000},"ttl_s":60}""")).isInstanceOf(TaskParse.Valid::class.java)
        assertThat(parse("""{"id":"b","text":"x","ts":${now + 30_000},"ttl_s":60}""")).isInstanceOf(TaskParse.Valid::class.java)
    }

    @Test
    fun `ttl is clamped`() {
        val t = (parse("""{"id":"c","text":"x","ttl_s":99999999}""") as TaskParse.Valid).task
        assertThat(t.ttlSeconds).isEqualTo(JarvisProtocol.MAX_TTL_S)
        val t2 = (parse("""{"id":"d","text":"x","ttl_s":-5}""") as TaskParse.Valid).task
        assertThat(t2.ttlSeconds).isEqualTo(1)
    }

    @Test
    fun `result encodes and round trips unicode and quotes`() {
        val bytes = JarvisProtocol.encodeResult("t1", ResultStatus.OK, "Готово: \"ok\"\nline2", now)
        val json = bytes.toString(Charsets.UTF_8)
        assertThat(json).contains("\"status\":\"ok\"")
        val back = JarvisProtocol.decodeResult(bytes)
        assertThat(back.id).isEqualTo("t1")
        assertThat(back.status).isEqualTo(ResultStatus.OK)
        assertThat(back.text).isEqualTo("Готово: \"ok\"\nline2")
        assertThat(back.v).isEqualTo(1)
    }

    @Test
    fun `huge result text is truncated`() {
        val back = JarvisProtocol.decodeResult(
            JarvisProtocol.encodeResult("t", ResultStatus.OK, "x".repeat(JarvisProtocol.MAX_RESULT_TEXT + 10), now)
        )
        assertThat(back.text.length).isLessThan(JarvisProtocol.MAX_RESULT_TEXT + 20)
        assertThat(back.text).endsWith("[…truncated]")
    }

    @Test
    fun `status payload`() {
        assertThat(JarvisProtocol.encodeStatus(true, now).toString(Charsets.UTF_8)).contains("\"state\":\"online\"")
        assertThat(JarvisProtocol.encodeStatus(false, now).toString(Charsets.UTF_8)).contains("\"state\":\"offline\"")
    }
}
