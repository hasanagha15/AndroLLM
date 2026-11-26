package io.androllm.core.appatlas.playbook

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.controller.NodeRef
import io.androllm.core.accessibility.controller.UiActionResult
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.origin.RemoteToolOrigin
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/** Branch W4 (gate rev 4): the production driver marks own actions only for Jarvis (remote-origin) runs. */
class AccessibilityPlaybookDriverMarkTest {

    private val controller = mockk<AccessibilityController>(relaxed = true)
    private val tracker = OwnRunTracker()
    private var now = 1_000L
    private val driver = AccessibilityPlaybookDriver(mockk<Context>(relaxed = true), controller, ToolConfirmationManager(), tracker)
        .also { it.elapsed = { now } }
    private val ref = NodeRef(index = 1, viewId = null, className = null)

    init {
        coEvery { controller.clickNodeRef(any(), any()) } answers { now += 200L; UiActionResult.ok("tapped") }
        coEvery { controller.scroll(any()) } answers { now += 100L; UiActionResult.ok("scrolled") }
    }

    @Test
    fun `fast path (owner) actions are never marked`() = runTest {
        driver.tap(ref, null)
        driver.scroll("down")
        assertThat(tracker.actionStartedAt).isEqualTo(-1L)
        assertThat(tracker.isOwn(now)).isFalse()
    }

    @Test
    fun `remote-origin actions are marked from start to end plus the mask`() = runTest {
        withContext(RemoteToolOrigin()) { driver.tap(ref, null) }
        assertThat(tracker.actionStartedAt).isEqualTo(1_000L)
        assertThat(tracker.actionEndedAt).isEqualTo(1_200L)
        assertThat(tracker.inAction).isFalse()
        assertThat(tracker.isOwn(1_200L + OwnRunTracker.OWN_ACTION_MASK_MS)).isTrue()
        assertThat(tracker.isOwn(1_200L + OwnRunTracker.OWN_ACTION_MASK_MS + 1)).isFalse()
        // a later owner (fast path) action does not move the mark
        driver.scroll("down")
        assertThat(tracker.actionEndedAt).isEqualTo(1_200L)
    }

    @Test
    fun `a failing remote action still ends the mark`() = runTest {
        coEvery { controller.clickNodeRef(any(), any()) } throws IllegalStateException("boom")
        runCatching { withContext(RemoteToolOrigin()) { driver.tap(ref, null) } }
        assertThat(tracker.inAction).isFalse()
        assertThat(tracker.actionEndedAt).isEqualTo(1_000L)
    }
}
