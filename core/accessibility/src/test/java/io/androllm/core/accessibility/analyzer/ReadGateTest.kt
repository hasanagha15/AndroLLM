package io.androllm.core.accessibility.analyzer

import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.controller.SensitiveAppGuard
import io.androllm.core.accessibility.controller.UIStateTracker
import io.androllm.core.accessibility.controller.UiContextSnapshot
import org.junit.Test

/** K B1: no bank text via the event tracker, fail-closed when the active window is unknown. */
class ReadGateTest {

    private val bank = "az.kapitalbank.mbanking"
    private val launcher = "com.sec.android.app.launcher"

    @Test
    fun `tracker drops bank focused text when the package changes`() {
        val t = UIStateTracker()
        t.onEvent(bank, AccessibilityEvent.TYPE_VIEW_FOCUSED, "android.widget.EditText", "4169 7388 1234 5678")
        assertThat(t.snapshot().focusedText).isNotEmpty()
        t.onEvent(launcher, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "Launcher", null)
        val ctx = t.snapshot()
        assertThat(ctx.focusedText).isEmpty()
        assertThat(ctx.selectedText).isEmpty()
        val gate = readGate(launcher, ctx, emptySet())
        assertThat(gate.hiddenReason).isNull()
        assertThat(gate.trackerFocused).isEmpty()
    }

    @Test
    fun `tracker text from another app is never used even if not cleared`() {
        // Defensive: text tagged with the bank package is ignored for any other active window.
        val ctx = UiContextSnapshot(currentPackage = launcher, focusedText = "AZN 1500", textPackage = bank)
        val gate = readGate(launcher, ctx, emptySet())
        assertThat(gate.trackerFocused).isEmpty()
        assertThat(gate.trackerSelected).isEmpty()
    }

    @Test
    fun `tracker text of the active app is still used`() {
        val ctx = UiContextSnapshot(currentPackage = launcher, focusedText = "Search", textPackage = launcher)
        assertThat(readGate(launcher, ctx, emptySet()).trackerFocused).isEqualTo("Search")
    }

    @Test
    fun `no active window and tracked app is a bank - hidden, not retryable`() {
        val ctx = UiContextSnapshot(currentPackage = bank, focusedText = "AZN 1500", textPackage = bank)
        val gate = readGate(null, ctx, emptySet())
        assertThat(gate.hiddenReason).contains("banking/payment")
        assertThat(gate.hiddenRetryable).isFalse()
        assertThat(gate.trackerFocused).isEmpty()
    }

    @Test
    fun `no active window and unknown app - refused, retryable, no text`() {
        val gate = readGate(null, UiContextSnapshot(focusedText = "x", textPackage = ""), emptySet())
        assertThat(gate.hiddenReason).isEqualTo(SensitiveAppGuard.UNKNOWN_WINDOW_REASON)
        assertThat(gate.hiddenRetryable).isTrue()
    }

    @Test
    fun `active window ordinary but tracked app is a bank - hidden`() {
        val gate = readGate(launcher, UiContextSnapshot(currentPackage = bank), emptySet())
        assertThat(gate.hiddenReason).contains("banking/payment")
    }

    @Test
    fun `user-added package is gated on both paths`() {
        val extras = setOf("com.example.bank")
        assertThat(readGate("com.example.bank", UiContextSnapshot(), extras).hiddenReason).isNotNull()
        assertThat(readGate(null, UiContextSnapshot(currentPackage = "com.example.bank"), extras).hiddenReason)
            .contains("banking/payment")
    }
}
