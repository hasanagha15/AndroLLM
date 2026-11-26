package io.androllm.core.appatlas.timing

import io.androllm.core.accessibility.timing.DeviceLoadMonitor
import io.androllm.core.appatlas.AppAtlasRepository
import io.androllm.core.tools.remote.RemoteDataResult
import io.androllm.core.tools.remote.RemoteDataTaskHandler
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Branch T: Jarvis pulls the learned transition timing next to playbooks.sync
 * (`playbooks.timings`, read-only). Content-free: package, "<playbook id>#<step>",
 * kind, UI type, run counts and milliseconds, plus the current load factor.
 */
@Singleton
class TimingSyncHandler @Inject constructor(
    private val model: TransitionTimingModel,
    private val load: DeviceLoadMonitor,
    private val atlas: AppAtlasRepository,
    private val playbooks: io.androllm.core.appatlas.playbook.PlaybookRepository
) : RemoteDataTaskHandler {

    override val types: Set<String> = setOf(TYPE)

    override suspend fun handle(type: String, body: JsonObject?): RemoteDataResult {
        if (type != TYPE) return RemoteDataResult(false, "unknown task type")
        if (!atlas.isEnabled) return RemoteDataResult(false, "disabled (App Atlas is off)")
        val limit = ((body?.get("limit") as? JsonPrimitive)?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val rows = model.snapshot(limit)
        // Review R4: never export (and drop) timings of apps that are uninstalled / excluded or protected NOW.
        val extras = playbooks.extras()
        val gone = rows.map { it.packageName }.distinct().filter { pkg ->
            val e = atlas.entry(pkg)
            e == null || e.sensitive || io.androllm.core.appatlas.index.SensitiveClassifier.reason(pkg, e.label, extras) != null
        }.toSet()
        if (gone.isNotEmpty()) model.forget(gone)
        return RemoteDataResult(true, render(rows.filter { it.packageName !in gone }, load.loadFactorAsync(), MAX_CHARS))
    }

    companion object {
        const val TYPE = "playbooks.timings"
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 300
        /** Under the 8000-char result cap of the link protocol. */
        const val MAX_CHARS = 7_500

        /** `{"k":1.23,"timings":[…],"more":bool}`; rows that do not fit are dropped (more = true). */
        fun render(rows: List<TimingRecord>, k: Double, maxChars: Int): String {
            var more = false
            val arr = buildJsonArray {
                var size = 40
                for (r in rows) {
                    val o = buildJsonObject {
                        put("package", r.packageName)
                        put("key", r.transitionKey)
                        put("kind", r.kind.wire)
                        put("ui", r.uiType.wire)
                        put("n", r.stats.n)
                        put("fails", r.stats.fails)
                        put("p50", r.stats.p50Ms.toLong())
                        put("p95", r.stats.p95Ms.toLong())
                    }
                    val len = o.toString().length + 1
                    if (size + len > maxChars) { more = true; break }
                    size += len
                    add(o)
                }
            }
            return "{\"k\":${String.format(Locale.ROOT, "%.2f", k)},\"timings\":$arr,\"more\":$more}"
        }
    }
}
