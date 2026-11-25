package io.androllm.core.tools.remote

import kotlinx.serialization.json.JsonObject

/** Outcome of a typed (data) task from Jarvis. [text] goes back in the encrypted result. */
data class RemoteDataResult(val ok: Boolean, val text: String)

/**
 * Branch N: a typed, non-agent task received over the Jarvis link (same
 * encrypted MQTT task channel, AES-GCM/HKDF protocol v2) — e.g.
 * `playbooks.sync` (push a playbook bundle) or `playbooks.relearn` (pull the
 * relearn requests). It runs WITHOUT a model turn and without tools; the
 * handler validates [body] itself. Bound with `@IntoSet` by the feature that
 * owns the type (core:appatlas for playbooks).
 */
interface RemoteDataTaskHandler {
    /** Task types this handler owns (`[a-z0-9._-]`). */
    val types: Set<String>

    suspend fun handle(type: String, body: JsonObject?): RemoteDataResult
}
