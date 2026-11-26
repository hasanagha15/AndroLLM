package io.androllm.core.appatlas.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLabelResolverTest {

    private val atlas = listOf(
        "Settings" to "com.android.settings",
        "YouTube" to "com.google.android.youtube",
        "Clock" to "com.sec.android.app.clockpackage"
    )

    @Test
    fun `step 5 - label Settings resolves to the system settings package`() {
        assertThat(AppLabelResolver.resolve("Settings", atlas)).isEqualTo("com.android.settings")
        assertThat(AppLabelResolver.resolve("Настройки", atlas)).isEqualTo("com.android.settings")
        assertThat(AppLabelResolver.resolve(" settings ", atlas)).isEqualTo("com.android.settings")
    }

    @Test
    fun `settings alias resolves even when settings is not indexed`() {
        assertThat(AppLabelResolver.resolve("Настройки", atlas.drop(1))).isEqualTo("com.android.settings")
    }

    @Test
    fun `vendor app also labelled Settings never wins over the system one`() {
        val list = listOf("Settings" to "com.vendor.launcher.settings", "Settings" to "com.android.settings")
        assertThat(AppLabelResolver.resolve("Settings", list)).isEqualTo("com.android.settings")
    }

    @Test
    fun `other labels and packages resolve, unknown ones do not`() {
        assertThat(AppLabelResolver.resolve("youtube", atlas)).isEqualTo("com.google.android.youtube")
        assertThat(AppLabelResolver.resolve("com.google.android.youtube", atlas)).isEqualTo("com.google.android.youtube")
        assertThat(AppLabelResolver.resolve("Telegram", atlas)).isNull()
        assertThat(AppLabelResolver.resolve("", atlas)).isNull()
    }
}
