package io.androllm.core.jarvistools.tesla

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TeslaSafetyPolicyTest {

    private val dangerous = listOf(
        "unlock", "remote_start", "open_trunk", "open_frunk", "door_unlock", "remote_start_drive",
        "actuate_trunk", "trunk", "frunk", "window_control", "windows_open", "vent_windows", "charge_port_open",
        "honk", "flash_lights"
    )

    @Test fun goldenAllowlistIsExactlyTierA() {
        assertThat(TeslaSafetyPolicy.ALLOWED).containsExactly(
            "climate_on", "climate_off", "set_temp", "lock", "charge_start", "charge_stop", "set_charge_limit"
        )
    }

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
    }

    @Test fun safeActionsAllowed() {
        for (a in listOf("climate_on", "climate_off", "lock", "charge_start", "charge_stop")) {
            assertThat(TeslaSafetyPolicy.evaluate(a)).isEqualTo(TeslaSafetyPolicy.Decision.Allowed(a, emptyMap()))
        }
    }

    @Test fun paramBoundaries() {
        fun ok(d: TeslaSafetyPolicy.Decision) = d is TeslaSafetyPolicy.Decision.Allowed
        assertThat(ok(TeslaSafetyPolicy.evaluate("set_temp", tempC = 15.0))).isTrue()
        assertThat(ok(TeslaSafetyPolicy.evaluate("set_temp", tempC = 28.0))).isTrue()
        assertThat(ok(TeslaSafetyPolicy.evaluate("set_temp", tempC = 14.9))).isFalse()
        assertThat(ok(TeslaSafetyPolicy.evaluate("set_temp", tempC = 28.1))).isFalse()
        assertThat(ok(TeslaSafetyPolicy.evaluate("set_temp"))).isFalse()
        assertThat(ok(TeslaSafetyPolicy.evaluate("set_charge_limit", percent = 50))).isTrue()
        assertThat(ok(TeslaSafetyPolicy.evaluate("set_charge_limit", percent = 100))).isTrue()
        assertThat(ok(TeslaSafetyPolicy.evaluate("set_charge_limit", percent = 49))).isFalse()
        assertThat(ok(TeslaSafetyPolicy.evaluate("set_charge_limit", percent = 101))).isFalse()
        assertThat(TeslaSafetyPolicy.evaluate("set_temp", tempC = 21.5))
            .isEqualTo(TeslaSafetyPolicy.Decision.Allowed("set_temp", mapOf("temp_c" to 21.5)))
    }
}
