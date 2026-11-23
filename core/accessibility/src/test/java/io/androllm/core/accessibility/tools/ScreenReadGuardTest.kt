package io.androllm.core.accessibility.tools

import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.analyzer.UiScreenSnapshot
import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.controller.SensitiveAppGuard
import io.androllm.core.tools.api.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/** K: ui_read_screen / scan_qr never return the content of a banking/payment, Tesla or WhatsApp screen. */
class ScreenReadGuardTest {

    private val bank = SensitiveAppGuard.PAYMENT_PACKAGES.first()
    private val reason = SensitiveAppGuard.screenReadBlockedReason(bank)!!

    @Test
    fun `hidden snapshot describes only the refusal`() {
        val snap = UiScreenSnapshot.hidden(bank, reason)
        val text = snap.describe()
        assertThat(text).contains("Screen hidden")
        assertThat(text).contains(bank)
        assertThat(snap.root).isNull()
        assertThat(snap.ocrLines).isEmpty()
    }

    @Test
    fun `ui_read_screen in a banking app is a clear non-retryable refusal`() = runTest {
        val c = mockk<AccessibilityController>()
        every { c.isConnected } returns true
        coEvery { c.readScreen() } returns UiScreenSnapshot.hidden(bank, reason)
        val result = UiReadScreenTool(c).execute(buildJsonObject { }) as ToolResult.Failure
        assertThat(result.retryable).isFalse()
        assertThat(result.summary).contains("banking/payment")
    }

    @Test
    fun `ui_read_screen in an ordinary app still reads`() = runTest {
        val c = mockk<AccessibilityController>()
        every { c.isConnected } returns true
        coEvery { c.readScreen() } returns UiScreenSnapshot(packageName = "com.android.chrome")
        val result = UiReadScreenTool(c).execute(buildJsonObject { })
        assertThat(result).isInstanceOf(ToolResult.Success::class.java)
    }

    @Test
    fun `scan_qr refuses to capture a WhatsApp screen`() = runTest {
        val c = mockk<AccessibilityController>()
        every { c.isConnected } returns true
        coEvery { c.screenReadBlockedReason() } returns SensitiveAppGuard.screenReadBlockedReason("com.whatsapp")
        val result = QrScanTool(c).execute(buildJsonObject { }) as ToolResult.Failure
        assertThat(result.retryable).isFalse()
        assertThat(result.summary).contains("Screen reading is blocked")
        coVerify(exactly = 0) { c.captureScreenshot() }
    }
}
