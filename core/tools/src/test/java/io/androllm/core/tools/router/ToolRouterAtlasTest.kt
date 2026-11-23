package io.androllm.core.tools.router

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolSpec
import org.junit.Test

/** Branch L: app-control requests offer the App Atlas tools first. */
class ToolRouterAtlasTest {

    private val router = ToolRouter()

    private fun spec(name: String, permission: ToolPermission? = null, category: ToolCategory = ToolCategory.INFORMATION) =
        ToolSpec(name = name, description = "Tool $name", permission = permission, category = category)

    private val base = listOf(
        spec("calculate", ToolPermission.CALCULATOR),
        spec("get_battery", ToolPermission.DEVICE, ToolCategory.DEVICE),
        spec("search_web", ToolPermission.SEARCH),
        spec("get_weather", ToolPermission.WEATHER),
        spec("send_sms", ToolPermission.SMS, ToolCategory.COMMUNICATION),
        spec("launch_app", ToolPermission.APPS, ToolCategory.MEDIA),
        spec("ui_click", ToolPermission.ACCESSIBILITY, ToolCategory.DEVICE)
    )
    private val withAtlas = base + spec("app_open_action", ToolPermission.APPS, ToolCategory.MEDIA) +
        spec("app_atlas_search", ToolPermission.APPS, ToolCategory.MEDIA)

    @Test
    fun `app control requests put atlas search, open action, launch_app first`() {
        listOf(
            "open YouTube and search for cats",
            "открой телеграм и напиши Али",
            "запусти спотифай",
            "Spotify tətbiqində mahnı aç",
            "otkroy instagram",
            "in the Uber app order a ride",
            "message mom on telegram app"
        ).forEach { q ->
            val names = router.route(q, hasAttachments = false, enabledTools = withAtlas).specs.map { it.name }
            assertThat(names.take(3)).containsExactly("app_atlas_search", "app_open_action", "launch_app").inOrder()
        }
    }

    @Test
    fun `non app requests are not reordered`() {
        listOf("what is the weather today", "battery level", "write a poem about rain").forEach { q ->
            val names = router.route(q, hasAttachments = false, enabledTools = withAtlas).specs.map { it.name }
            assertThat(names.firstOrNull()).isNotEqualTo("app_atlas_search")
        }
        assertThat(router.isAppControlRequest("what's the weather")).isFalse()
    }

    @Test
    fun `payment keeps launch_app first and no atlas`() {
        val routed = router.route("open my bank app and pay the bill", hasAttachments = false, enabledTools = withAtlas)
        assertThat(routed.specs.first().name).isEqualTo("launch_app")
        assertThat(routed.reason).doesNotContain("app atlas first")
    }

    @Test
    fun `math stays calculator only`() {
        val names = router.route("open calculator and compute 2+2", hasAttachments = false, enabledTools = withAtlas).specs.map { it.name }
        assertThat(names.firstOrNull()).isNotEqualTo("app_atlas_search")
    }

    @Test
    fun `without atlas tools enabled nothing changes`() {
        val a = router.route("open YouTube", hasAttachments = false, enabledTools = base)
        assertThat(a.specs.map { it.name }).doesNotContain("app_atlas_search")
    }
}
