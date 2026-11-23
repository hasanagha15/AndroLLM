package io.androllm.core.accessibility.controller

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** K B1(b) + N1: full-display capture is fail-closed and checks every visible app window. */
class ScreenCaptureGateTest {

    private val bank = "az.pashabank.mobilebank"
    private val chat = "io.androllm.app"

    @Test fun unknownActiveWindowRefused() {
        assertThat(SensitiveAppGuard.screenCaptureBlockedReason(null, chat, listOf(chat)))
            .isEqualTo(SensitiveAppGuard.UNKNOWN_WINDOW_REASON)
        assertThat(SensitiveAppGuard.screenCaptureBlockedReason("", "", emptyList()))
            .isEqualTo(SensitiveAppGuard.UNKNOWN_WINDOW_REASON)
    }

    @Test fun trackedBankRefusedEvenIfActiveWindowIsOrdinary() {
        assertThat(SensitiveAppGuard.screenCaptureBlockedReason(chat, bank, listOf(chat))).contains("banking/payment")
    }

    @Test fun bankInSplitScreenOrPipRefused() {
        val r = SensitiveAppGuard.screenCaptureBlockedReason(chat, chat, listOf(chat, bank))
        assertThat(r).contains("split screen")
        assertThat(SensitiveAppGuard.screenCaptureBlockedReason(chat, chat, listOf(chat, "com.whatsapp"))).isNotNull()
    }

    @Test fun unidentifiedAppWindowRefused() {
        assertThat(SensitiveAppGuard.screenCaptureBlockedReason(chat, chat, listOf(chat, null))).contains("could not be identified")
    }

    @Test fun userAddedPackageVisibleRefused() {
        assertThat(
            SensitiveAppGuard.screenCaptureBlockedReason(chat, chat, listOf("com.example.bank"), setOf("com.example.bank"))
        ).isNotNull()
    }

    @Test fun ordinaryScreenAllowed() {
        assertThat(SensitiveAppGuard.screenCaptureBlockedReason(chat, chat, listOf(chat, "com.android.chrome"))).isNull()
    }
}
