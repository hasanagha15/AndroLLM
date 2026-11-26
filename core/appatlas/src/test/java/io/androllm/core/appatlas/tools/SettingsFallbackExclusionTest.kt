package io.androllm.core.appatlas.tools

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.AppAtlasRepository
import io.androllm.core.appatlas.learn.LearnedPathRecorder
import io.androllm.core.tools.api.ToolResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * Branch U it.2 (gate B2): the ACTION_SETTINGS fallback must not bypass the
 * owner's App Atlas exclusion ("never opened by atlas tools") — for local and
 * remote tasks alike (both go through this tool).
 */
class SettingsFallbackExclusionTest {

    private val context = mockk<Context>(relaxed = true).also { every { it.packageName } returns "io.androllm.app" }
    private val repository = mockk<AppAtlasRepository>(relaxed = true)
    private val tool = AppOpenActionTool(context, repository, mockk<LearnedPathRecorder>(relaxed = true))

    private fun setUp(excluded: Set<String>) {
        every { repository.isEnabled } returns true
        coEvery { repository.entry(any()) } returns null // excluded apps are never indexed
        coEvery { repository.entries() } returns emptyList()
        coEvery { repository.currentSensitiveExtras() } returns emptySet()
        every { repository.excludedPackages() } returns excluded
    }

    @Test
    fun `excluded Settings gets the old not-in-atlas refusal and nothing is launched`() = runTest {
        setUp(setOf("com.android.settings"))
        for (pkg in listOf("Settings", "Настройки", "com.android.settings")) {
            val r = tool.execute(buildJsonObject { put("package", pkg); put("action_id", "open") })
            assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
            assertThat(r.summary).contains("is not in the App Atlas")
            assertThat((r as ToolResult.Failure).retryable).isFalse()
        }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `fallback rule - only system Settings, only open, never when excluded`() {
        assertThat(AppLabelResolver.settingsFallbackAllowed("com.android.settings", "open", emptySet())).isTrue()
        assertThat(AppLabelResolver.settingsFallbackAllowed("com.android.settings", "open", setOf(" com.android.settings "))).isFalse()
        assertThat(AppLabelResolver.settingsFallbackAllowed("com.android.settings", "search", emptySet())).isFalse()
        assertThat(AppLabelResolver.settingsFallbackAllowed("com.example.app", "open", emptySet())).isFalse()
    }
}
