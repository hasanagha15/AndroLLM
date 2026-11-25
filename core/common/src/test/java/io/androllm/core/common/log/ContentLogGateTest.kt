package io.androllm.core.common.log

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch Q (gate B1–B3): a grep gate over every module's `src/main`. A log
 * call (Timber / android.util.Log / RuntimeLogger) must not interpolate a
 * conversation-content variable (prompt, transcript, reply, TTS sentence,
 * tool arguments / output, query …) unless it goes through [ContentLog].
 * Lengths, sizes, ids and names are allowed.
 *
 * Exempt: MemoryRepository — its `logger` is the in-memory MemoryLogger of
 * the Memory Inspector, not logcat (owner decision).
 */
class ContentLogGateTest {

    private val logCall = Regex("""(Timber(\.tag\([^)]*\))?\.[a-z]+\(|Log\.[diwev]\(|logger\.[diwev]\()""")
    private val vars = "(trimmed|prompt|text|content|transcript|sentence|cleaned|covered|normalized|reply|query|expr|loc|" +
        "userMessage|modelOutput|finalResponse|goal|request|summary|argsString|arguments|output|utterance)"
    private val safe = """(?!\s*\.(length|size|id|first|isBlank|isEmpty|name)\b)"""
    private val interpolation = Regex("""\$\{?""" + vars + """\b""" + safe)
    private val formatArg = Regex(""",\s*(\w+\.)?""" + vars + """\b""" + safe)
    private val exempt = setOf("MemoryRepository.kt")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "repo root not found" }
    }

    /** Offending log lines in [source] (exposed for the self-test below). */
    fun scan(name: String, source: String): List<String> {
        val lines = source.lines()
        val hits = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            if (!logCall.containsMatchIn(line)) continue
            val call = StringBuilder(line)
            if (line.trimEnd().endsWith("(")) {
                var j = i + 1
                while (j < lines.size && j < i + 8) {
                    call.append(' ').append(lines[j])
                    if (lines[j].trim().startsWith(")")) break
                    j++
                }
            }
            val text = call.toString()
            if ("ContentLog" in text) continue
            val args = text.substring(text.lastIndexOf('"') + 1)
            if (interpolation.containsMatchIn(text) || ("%s" in text && formatArg.containsMatchIn(args))) {
                hits += "$name:${i + 1}: ${line.trim().take(160)}"
            }
        }
        return hits
    }

    @Test
    fun `no log call in src-main interpolates conversation content`() {
        val root = repoRoot()
        val files = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "third_party" }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") && it.name !in exempt }
            .toList()
        assertTrue("scanned too few files: ${files.size}", files.size > 200)
        val hits = files.flatMap { scan(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
        assertTrue("content in logs (wrap with ContentLog):\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun `the gate catches the leaks fixed in branch Q`() {
        val leaky = """
            android.util.Log.i(TAG, "SEND CLICK prompt=\"${'$'}trimmed\" (${'$'}{trimmed.length} chars)")
            Timber.d("TTS: speaking '${'$'}{covered}'")
            Timber.i("calculator with expression '${'$'}expr'")
            Timber.i("STT: transcript='%s'", trimmed.take(80))
        """.trimIndent()
        assertTrue(scan("leaky.kt", leaky).size == 4)
        val clean = """
            android.util.Log.i(TAG, "SEND CLICK prompt=${'$'}{ContentLog.text(trimmed)} (${'$'}{trimmed.length} chars)")
            Timber.i("tool '${'$'}{call.name}' output=%dB", summary.length)
            Timber.i("MESSAGE CREATED id=${'$'}{userMessage.id}")
        """.trimIndent()
        assertTrue(scan("clean.kt", clean).isEmpty())
    }
}
