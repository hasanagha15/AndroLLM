package io.androllm.core.tools.tool.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Branch U (step 5): the APP_CLOCK fallback must run when SHOW_ALARMS throws. */
class ClockLauncherTest {

    @Test
    fun `a SecurityException on the first intent falls through to the next candidate`() {
        val started = mutableListOf<String>()
        val ok = ClockLauncher.firstThatStarts<String>(listOf({ "SHOW_ALARMS" }, { "APP_CLOCK" })) { intent ->
            if (intent == "SHOW_ALARMS") throw SecurityException("requires com.android.alarm.permission.SET_ALARM")
            started.add(intent)
        }
        assertThat(ok).isTrue()
        assertThat(started).containsExactly("APP_CLOCK")
    }

    @Test
    fun `null candidates are skipped and all failing returns false`() {
        val ok = ClockLauncher.firstThatStarts<String>(listOf({ null }, { "pkg" })) { throw IllegalStateException() }
        assertThat(ok).isFalse()
    }

    @Test
    fun `first working candidate wins`() {
        val started = mutableListOf<String>()
        assertThat(ClockLauncher.firstThatStarts<String>(listOf({ "a" }, { "b" })) { started.add(it) }).isTrue()
        assertThat(started).containsExactly("a")
    }

    @Test
    fun `manifest declares the real SET_ALARM permission name`() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains("android:name=\"com.android.alarm.permission.SET_ALARM\"")
        assertThat(manifest).doesNotContain("android:name=\"android.permission.SET_ALARM\"")
    }
}
