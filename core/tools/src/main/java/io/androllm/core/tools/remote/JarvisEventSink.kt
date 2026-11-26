package io.androllm.core.tools.remote

import kotlinx.serialization.json.JsonObject

/**
 * Branch R1: a phone-originated event for Jarvis (e.g. `apps.changed`), sent
 * over the existing encrypted MQTT link (protocol v2, its own HKDF key and
 * AAD, topic `jarvis/phone/event`). Fire-and-forget: the link keeps a small
 * bounded outbox and delivers it once connected; when the link is turned off
 * the event is dropped. [body] must carry ids / package names / numbers only,
 * never user content. Bound by core:jarvislink; optional for its users.
 */
interface JarvisEventSink {
    fun publish(type: String, body: JsonObject)
}
