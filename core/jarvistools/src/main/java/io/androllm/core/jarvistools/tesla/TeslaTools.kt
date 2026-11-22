package io.androllm.core.jarvistools.tesla

import io.androllm.core.jarvistools.confirm.SideEffectBudget
import io.androllm.core.jarvistools.confirm.SideEffectGate
import io.androllm.core.tools.api.PrivacyLevel
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolCost
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.tool.impl.ToolArgs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Read-only: car status from tesla-bridge. No confirmation (no side effects). */
@Singleton
class TeslaStatusTool @Inject constructor(
    private val client: TeslaBridgeClient
) : Tool {

    override val spec = ToolSpec(
        name = "tesla_status",
        description = "Owner's Tesla status via tesla-bridge: battery %, range km, charging, locked, climate, inside/outside temperature, data age. Read-only. fresh=true asks the car for new data (costs a little, never wakes the car).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("fresh") { put("type", "boolean"); put("description", "Bypass the 10-minute cache") }
            }
        },
        permission = null,
        requiresConfirmation = false,
        category = ToolCategory.INFORMATION,
        supportedTasks = listOf("tesla", "car", "машина", "тесла", "battery of the car", "заряд машины"),
        // Not cacheable: the bridge already caches; a second cache would show "unlocked" after a lock.
        cacheable = false,
        estimatedLatencyMs = 3000L,
        privacyLevel = PrivacyLevel.SENSITIVE,
        cost = ToolCost.NETWORK,
        failureModes = listOf("network", "auth", "asleep", "budget"),
        capabilities = listOf("tesla.read")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        when (val r = client.status(fresh = ToolArgs.bool(arguments, "fresh"))) {
            is TeslaBridgeClient.Outcome.Ok -> ToolResult.Success(summarize(r.data), r.data)
            // retryable=false: the base coordinator's retries/alternative tools only add delay here.
            is TeslaBridgeClient.Outcome.Error -> ToolResult.Failure(r.message, retryable = false)
        }

    internal fun summarize(d: JsonObject): String {
        fun s(k: String) = (d[k] as? JsonPrimitive)?.contentOrNull
        val parts = mutableListOf<String>()
        s("battery_level")?.let { parts += "battery $it%" }
        s("range_km")?.let { parts += "range $it km" }
        s("charging_state")?.let { parts += "charging: $it" }
        s("locked")?.let { parts += if (it == "true") "locked" else "UNLOCKED" }
        s("climate_on")?.let { parts += "climate ${if (it == "true") "on" else "off"}" }
        s("inside_temp_c")?.let { parts += "inside $it°C" }
        s("outside_temp_c")?.let { parts += "outside $it°C" }
        s("age_s")?.toDoubleOrNull()?.let { parts += "data ${(it / 60).toInt()} min old" }
        return if (parts.isEmpty()) "Tesla status received." else "Tesla: " + parts.joinToString(", ") + "."
    }
}

/**
 * Side effect: a SAFE (tier A) Tesla command. Only [TeslaSafetyPolicy.ALLOWED]
 * is reachable (schema enum + policy + client re-check + the bridge's own
 * phone allowlist). Unlock, remote start, trunk/frunk, windows, horn, lights
 * and the charge port are impossible. Always confirmed by the owner.
 */
@Singleton
class TeslaCommandTool @Inject constructor(
    private val client: TeslaBridgeClient,
    private val gate: SideEffectGate
) : Tool {

    override val spec = ToolSpec(
        name = "tesla_command",
        description = "Send a SAFE command to the owner's Tesla via tesla-bridge (always asks the user first): climate_on, climate_off, set_temp (temp_c 15..28), lock, charge_start, charge_stop, set_charge_limit (percent 50..100). Unlocking, remote start, trunk/frunk, windows, horn and lights are NOT possible.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { TeslaSafetyPolicy.ALLOWED.sorted().forEach { add(it) } }
                }
                putJsonObject("temp_c") { put("type", "number"); put("description", "For set_temp: 15..28") }
                putJsonObject("percent") { put("type", "integer"); put("description", "For set_charge_limit: 50..100") }
            }
            putJsonArray("required") { add("action") }
        },
        permission = null,
        requiresConfirmation = true,
        category = ToolCategory.DEVICE,
        confirmationPrompt = "send the Tesla command {action}",
        executionTimeoutMs = SideEffectBudget.ONE_CALL_MS,
        supportedTasks = listOf("tesla", "car climate", "кондиционер машины", "закрой машину", "зарядка машины"),
        cacheable = false,
        estimatedLatencyMs = 15_000L,
        privacyLevel = PrivacyLevel.SENSITIVE,
        cost = ToolCost.NETWORK,
        failureModes = listOf("network", "auth", "asleep", "budget", "forbidden"),
        capabilities = listOf("tesla.command.safe")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val started = System.currentTimeMillis()
        // Long → Int without silent truncation: out-of-Int values become an invalid percent.
        val percent = ToolArgs.long(arguments, "percent")?.let { if (it in 0L..1000L) it.toInt() else -1 }
        val decision = TeslaSafetyPolicy.evaluate(
            rawAction = ToolArgs.str(arguments, "action", "command"),
            tempC = ToolArgs.double(arguments, "temp_c") ?: ToolArgs.double(arguments, "temperature"),
            percent = percent
        )
        if (decision is TeslaSafetyPolicy.Decision.Refused) {
            return ToolResult.Failure("Refused: ${decision.reason}.", retryable = false)
        }
        decision as TeslaSafetyPolicy.Decision.Allowed
        // Check URL + token BEFORE asking the owner (no pointless card).
        client.ready()?.let { return ToolResult.Failure(it, retryable = false) }
        if (!gate.ensureConfirmed(spec, arguments)) {
            return ToolResult.Failure("The user declined the action.", retryable = false)
        }
        if (!SideEffectBudget.hasNetworkBudget(started, spec.executionTimeoutMs!!)) {
            return ToolResult.Failure("Confirmation came too late — nothing was sent. Ask again.", retryable = false)
        }
        return when (val r = client.command(decision)) {
            is TeslaBridgeClient.Outcome.Ok -> ToolResult.Success(
                "Tesla: ${decision.action} done.",
                buildJsonObject { put("action", decision.action); put("ok", true) }
            )
            is TeslaBridgeClient.Outcome.Error -> ToolResult.Failure(r.message, retryable = false)
        }
    }
}
