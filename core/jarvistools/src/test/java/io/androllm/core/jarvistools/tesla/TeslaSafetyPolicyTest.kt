package io.androllm.core.jarvistools.tesla

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TeslaSafetyPolicyTest {

    private val dangerous = listOf(
        "unlock", "remote_start", "open_trunk", "open_frunk", "door_unlock", "remote_start_drive",
        "actuate_trunk", "trunk", "frunk", "window_control", "windows_open", "vent_windows", "charge_port_open"
    )

    @Test fun dangerousActionsAreNeverAllowed() {
        for (a in dangerous) {
            assertThat(TeslaSafetyPolicy.isAllowed(a)).isFalse()
            assertThat(TeslaSafetyPolicy.evaluate(a)).isInstanceOf(TeslaSafetyPolicy.Decision.Refused::class.java)
        }
    }

    @Test fun obfuscatedSpellingsAreRefused() {
        val tricks = listOf(
            "UNLOCK", " Unlock ", "remote-start", "Open Trunk", "unlock;lock", "..%2Funlock",
            "lock%2F..%2Funlock", "lock?x=unlock", "lock unlock", ""
        )
        for (a in tricks) {
            assertThat(TeslaSafetyPolicy.evaluate(a)).isInstanceOf(TeslaSafetyPolicy.Decision.Refused::class.java)
        }
        assertThat(TeslaSafetyPolicy.evaluate(null)).isInstanceOf(TeslaSafetyPolicy.Decision.Refused::class.java)
    }

    @Test fun allowlistAndForbiddenAreDisjoint() {
        assertThat(TeslaSafetyPolicy.ALLOWED.intersect(TeslaSafetyPolicy.FORBIDDEN)).isEmpty()
        assertThat(TeslaSafetyPolicy.ALLOWED).containsNoneOf("unlock", "remote_start", "open_trunk", "open_frunk")
    }

    @Test fun safeActionsAllowed() {
        for (a in listOf("climate_on", "climate_off", "lock", "charge_start", "charge_stop", "honk", "flash_lights")) {
            assertThat(TeslaSafetyPolicy.evaluate(a)).isEqualTo(TeslaSafetyPolicy.Decision.Allowed(a, emptyMap()))
        }
    }

    @Test fun paramRangesEnforced() {
        assertThat(TeslaSafetyPolicy.evaluate("set_temp", tempC = 21.5))
            .isEqualTo(TeslaSafetyPolicy.Decision.Allowed("set_temp", mapOf("temp_c" to 21.5)))
        assertThat(TeslaSafetyPolicy.evaluate("set_temp", tempC = 35.0)).isInstanceOf(TeslaSafetyPolicy.Decision.Refused::class.java)
        assertThat(TeslaSafetyPolicy.evaluate("set_temp")).isInstanceOf(TeslaSafetyPolicy.Decision.Refused::class.java)
        assertThat(TeslaSafetyPolicy.evaluate("set_charge_limit", percent = 80))
            .isEqualTo(TeslaSafetyPolicy.Decision.Allowed("set_charge_limit", mapOf("percent" to 80)))
        assertThat(TeslaSafetyPolicy.evaluate("set_charge_limit", percent = 20)).isInstanceOf(TeslaSafetyPolicy.Decision.Refused::class.java)
    }
}
