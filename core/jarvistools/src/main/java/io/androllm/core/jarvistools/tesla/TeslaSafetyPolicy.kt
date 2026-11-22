package io.androllm.core.jarvistools.tesla

/**
 * Client-side allowlist for the phone ("source" = AndroLLM app). Mirrors the
 * owner's Jarvis policy (~/jarvis/tesla-policy.json on pi-brain):
 * tier A only (climate_on/off, set_temp, lock, charge_start/stop,
 * set_charge_limit) — like the agent source `openjarvis`. Tier B (honk,
 * flash_lights, charge_port_open) is NOT available from the phone. The bridge
 * enforces the same list server-side for the phone token (policy.PHONE_ACTIONS).
 *
 * The four dangerous bridge actions (unlock, remote_start, open_trunk,
 * open_frunk) and anything else not listed are refused BEFORE a request is
 * built — independent of the bridge's own ALLOW_* flags.
 */
object TeslaSafetyPolicy {

    /** Bridge action names (POST /api/command/<name>) the phone may send. */
    val ALLOWED: Set<String> = setOf(
        "climate_on", "climate_off", "set_temp",
        "lock",
        "charge_start", "charge_stop", "set_charge_limit"
    )

    /** Never reachable from the app, whatever the bridge or the model says. */
    val FORBIDDEN: Set<String> = setOf(
        "unlock", "door_unlock", "remote_start", "remote_start_drive",
        "open_trunk", "open_frunk", "actuate_trunk", "trunk", "frunk",
        "window_control", "windows_open", "vent_windows", "sun_roof_control",
        "charge_port_open", "charge_port_door_open", "set_valet_mode", "speed_limit_deactivate",
        "set_sentry_mode", "door_lock_unlock",
        "honk", "honk_horn", "flash_lights"
    )

    val TEMP_RANGE_C = 15.0..28.0
    val CHARGE_LIMIT_RANGE = 50..100

    sealed interface Decision {
        data class Allowed(val action: String, val params: Map<String, Number>) : Decision
        data class Refused(val reason: String) : Decision
    }

    /** Normalizes and validates one requested command. */
    fun evaluate(rawAction: String?, tempC: Double? = null, percent: Int? = null): Decision {
        val action = rawAction?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')
            ?: return Decision.Refused("no action given")
        if (action in FORBIDDEN || !isAllowed(action)) {
            return Decision.Refused("'$action' is not allowed from the phone (safety allowlist)")
        }
        return when (action) {
            "set_temp" -> {
                val t = tempC ?: return Decision.Refused("set_temp needs temp_c (15..28)")
                if (t !in TEMP_RANGE_C) Decision.Refused("temp_c must be 15..28 °C")
                else Decision.Allowed(action, mapOf("temp_c" to t))
            }
            "set_charge_limit" -> {
                val p = percent ?: return Decision.Refused("set_charge_limit needs percent (50..100)")
                if (p !in CHARGE_LIMIT_RANGE) Decision.Refused("percent must be 50..100")
                else Decision.Allowed(action, mapOf("percent" to p))
            }
            else -> Decision.Allowed(action, emptyMap())
        }
    }

    /** Exact-match allowlist check; forbidden names can never pass. */
    fun isAllowed(action: String): Boolean = action in ALLOWED && action !in FORBIDDEN
}
