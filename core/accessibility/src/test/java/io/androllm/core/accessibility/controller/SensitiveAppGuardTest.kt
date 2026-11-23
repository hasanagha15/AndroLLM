package io.androllm.core.accessibility.controller

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitiveAppGuardTest {

    @Test fun teslaAndWhatsAppBlocked() {
        for (p in listOf("com.teslamotors.tesla", "com.whatsapp", "com.whatsapp.w4b")) {
            assertThat(SensitiveAppGuard.blockedReason(p)).isNotNull()
        }
        assertThat(SensitiveAppGuard.blockedReason("com.whatsapp")).contains("whatsapp_reply")
        assertThat(SensitiveAppGuard.blockedReason("com.teslamotors.tesla")).contains("tesla_command")
    }

    @Test fun otherAppsAllowed() {
        for (p in listOf("com.android.chrome", "com.google.android.apps.maps", "com.whatsapp2", "", null)) {
            assertThat(SensitiveAppGuard.blockedReason(p)).isNull()
        }
    }
}

class SensitiveAppGuardPaymentTest {

    @Test fun builtInPaymentAppsBlocked() {
        for (p in SensitiveAppGuard.PAYMENT_PACKAGES) {
            assertThat(SensitiveAppGuard.blockedReason(p)).contains("banking/payment")
        }
        assertThat(SensitiveAppGuard.blockedReason("az.kapitalbank.mbanking")).isNotNull()
    }

    @Test fun userAddedPackagesBlockedAndInvalidDropped() {
        val parsed = SensitiveAppGuard.parsePackages("com.example.bank, not a package; org.x.wallet\n..bad")
        assertThat(parsed).containsExactly("com.example.bank", "org.x.wallet")
        assertThat(SensitiveAppGuard.blockedReason("com.example.bank", parsed)).isNotNull()
        assertThat(SensitiveAppGuard.blockedReason("com.example.other", parsed)).isNull()
        // No global state: without the extras the same package is not blocked.
        assertThat(SensitiveAppGuard.blockedReason("com.example.bank")).isNull()
    }

    @Test fun builtInIdsAreWellFormedAndDisjointFromTeslaWhatsApp() {
        for (p in SensitiveAppGuard.PAYMENT_PACKAGES) {
            assertThat(SensitiveAppGuard.parsePackages(p)).containsExactly(p)
        }
        assertThat(SensitiveAppGuard.PAYMENT_PACKAGES.intersect(SensitiveAppGuard.BLOCKED_PACKAGES)).isEmpty()
    }
}

/** K: screen reading (tree / screenshot / OCR) is refused in the same apps. */
class SensitiveAppGuardScreenReadTest {

    @Test fun paymentAppsScreenHidden() {
        for (p in SensitiveAppGuard.PAYMENT_PACKAGES) {
            assertThat(SensitiveAppGuard.screenReadBlockedReason(p)).contains("banking/payment")
        }
    }

    @Test fun userAddedPackagesScreenHidden() {
        val extras = setOf("com.example.bank")
        assertThat(SensitiveAppGuard.screenReadBlockedReason("com.example.bank", extras)).contains("Screen reading is blocked")
        assertThat(SensitiveAppGuard.screenReadBlockedReason("com.example.bank")).isNull()
    }

    @Test fun teslaAndWhatsAppScreenHiddenWithDedicatedToolHint() {
        assertThat(SensitiveAppGuard.screenReadBlockedReason("com.whatsapp")).contains("whatsapp_inbox")
        assertThat(SensitiveAppGuard.screenReadBlockedReason("com.whatsapp.w4b")).contains("whatsapp_inbox")
        assertThat(SensitiveAppGuard.screenReadBlockedReason("com.teslamotors.tesla")).contains("tesla_status")
    }

    @Test fun ordinaryAppsReadable() {
        for (p in listOf("com.android.chrome", "com.google.android.youtube", "com.whatsapp2", "", null)) {
            assertThat(SensitiveAppGuard.screenReadBlockedReason(p)).isNull()
        }
    }
}
