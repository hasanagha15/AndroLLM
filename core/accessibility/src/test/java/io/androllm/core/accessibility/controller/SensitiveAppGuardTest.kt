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
