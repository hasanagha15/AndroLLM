package io.androllm.core.tools.tool.impl

import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolBackend
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Reads the notifications currently shown on the device ("what just pinged?").
 * Requires Notification access, which the user enables once in Android
 * settings; the tool explains how when it is missing.
 */
/**
 * Decides which apps' notifications are content-hidden (K: banking/payment,
 * Tesla, WhatsApp: amounts, balances, OTP codes, other people's messages).
 * Bound by core:accessibility to SensitiveAppGuard + the user's list.
 */
interface NotificationPrivacyFilter {
    suspend fun hideContent(packageName: String): Boolean
}

@Singleton
class NotificationTool @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val privacy: NotificationPrivacyFilter
) : Tool {

    override val spec = ToolSpec(
        name = "read_notifications",
        description = "Read the notifications currently visible on the device, newest first.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max notifications to return (default 5)")
                }
            }
        },
        permission = ToolPermission.NOTIFICATIONS,
        category = ToolCategory.INFORMATION,
        requiresConfirmation = true,
        supportedBackends = setOf(ToolBackend.LOCAL, ToolBackend.CLOUD),
        availableOnDevice = true,
        worksLocally = true
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!AndroNotificationListener.isEnabled) {
            return ToolResult.Failure(
                "Notification access is not enabled. Open Android Settings → Apps → AndroLLM → Notifications → Notification access and allow it, then ask again."
            )
        }
        val limit = ToolArgs.int(arguments, "limit", 5).coerceIn(1, 20)
        val items = AndroNotificationListener.active.toList().sortedByDescending { it.postedAt }.take(limit)
        if (items.isEmpty()) {
            return ToolResult.Success("No notifications are currently visible.")
        }
        val hidden = items.map { it.packageName }.distinct()
            .filter { runCatching { privacy.hideContent(it) }.getOrDefault(true) }
            .toSet()
        val (summary, data) = format(items, hidden) { pkg -> appLabel(pkg) }
        return ToolResult.Success(summary = summary, data = data)
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        /**
         * Builds the summary + data. For [hiddenPackages] only "notification
         * from <app label>" is returned: no title, no text (OTP safety).
         */
        internal fun format(
            items: List<AndroNotificationListener.ActiveNotification>,
            hiddenPackages: Set<String>,
            label: (String) -> String
        ): Pair<String, JsonObject> {
            val sb = StringBuilder("Notifications:")
            val dataItems = mutableListOf<JsonObject>()
            for (item in items) {
                if (item.packageName in hiddenPackages) {
                    val app = label(item.packageName)
                    dataItems += buildJsonObject {
                        put("app", item.packageName)
                        put("hidden", true)
                    }
                    sb.append(" Notification from ").append(app).append(" (content hidden for privacy).")
                    continue
                }
                dataItems += buildJsonObject {
                    put("app", item.packageName)
                    put("title", item.title)
                    put("text", item.text.take(200))
                }
                val itemLabel = item.title.ifBlank { item.packageName }
                val body = item.text.take(100)
                sb.append(' ').append(itemLabel)
                if (body.isNotBlank()) sb.append(" — ").append(body)
            }
            val data = buildJsonObject { putJsonArray("notifications") { dataItems.forEach { add(it) } } }
            return sb.toString() to data
        }
    }
}
