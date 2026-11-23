package io.androllm.core.appatlas

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.androllm.core.appatlas.index.SchemePolicy
import io.androllm.core.appatlas.model.ActionKind
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.AtlasAction
import io.androllm.core.appatlas.tools.ActionGuard
import org.junit.Test

/** Branch M: https share / compose links are communication actions, never opened by the atlas. */
class ComposeLinkTest {

    @Test
    fun `prefilled draft links are detected`() {
        listOf(
            "https://t.me/share/url?url=https%3A%2F%2Fx.io&text=hello",
            "https://telegram.me/share/url?text=hi",
            "https://t.me/someuser?text=pay%20me",
            "https://wa.me/994501234567?text=Hello",
            "https://WA.ME/994501234567?TEXT=Hello",
            "https://api.whatsapp.com/send?phone=994501234567&text=hi",
            "https://api.whatsapp.com/send/?phone=1",
            "https://web.whatsapp.com/send?phone=1&text=x",
            "https://mail.google.com/mail/?view=cm&fs=1&to=a@b.c&su=Hi&body=x",
            "https://mail.google.com/mail/u/0/#compose",
            "https://mail.google.com/mail/u/0/?fs=1&tf=cm&to=a@b.c",
            "https://outlook.live.com/mail/0/deeplink/compose?to=a@b.c&subject=x",
            "https://outlook.office.com/mail/deeplink/compose?body=x",
            "https://compose.mail.yahoo.com/?to=a@b.c",
            "https://mail.yahoo.com/d/compose/123",
            "https://twitter.com/intent/tweet?text=x",
            "https://x.com/intent/post?text=x",
            "https://www.facebook.com/sharer/sharer.php?u=https%3A%2F%2Fx.io",
            "https://m.facebook.com/sharer.php?u=x",
            "https://www.linkedin.com/sharing/share-offsite/?url=x",
            "https://m.me/someone?text=hello",
            "https://vk.com/share.php?url=x",
            "https://example.com/share?text=hello",
            "https://example.com/compose?to=x",
            "https://t.me/%73hare/url?text=encoded"
        ).forEach { url ->
            assertWithMessage(url).that(SchemePolicy.isComposeWebUrl(url)).isTrue()
        }
    }

    @Test
    fun `ordinary web links are not compose links`() {
        listOf(
            "https://video.example.com/results?search_query=cats",
            "https://t.me/durov",
            "https://wa.me/994501234567",
            "https://mail.google.com/mail/u/0/#inbox",
            "https://outlook.live.com/mail/0/inbox",
            "https://x.com/someone/status/1",
            "https://www.facebook.com/somepage",
            "https://example.com/share/photos/abc",
            "https://maps.google.com/?q=Baku",
            "examplevideo://watch/v/abc",
            "geo:40.4,49.8",
            null,
            ""
        ).forEach { url ->
            assertWithMessage(url.toString()).that(SchemePolicy.isComposeWebUrl(url)).isFalse()
        }
    }

    private val open = AtlasAction("open", ActionKind.LAUNCH, "Open")
    private val tgLink = AtlasAction(
        "link:1", ActionKind.DEEP_LINK, "Telegram (t.me)", uri = "https://t.me/", scheme = "https", host = "t.me"
    )
    private val waLink = AtlasAction(
        "link:2", ActionKind.DEEP_LINK, "WhatsApp (api)", uri = "https://api.whatsapp.com/", scheme = "https", host = "api.whatsapp.com"
    )
    private val anyHttps = AtlasAction("link:3", ActionKind.DEEP_LINK, "Browser", uri = "https://example.com/", scheme = "https")
    private val storedShare = AtlasAction(
        "link:4", ActionKind.DEEP_LINK, "Share", uri = "https://t.me/share/url", scheme = "https", host = "t.me"
    )
    private val gmailShortcut = AtlasAction(
        "shortcut:compose", ActionKind.SHORTCUT, "Compose", component = "com.example.mail/.Main",
        intentAction = "android.intent.action.VIEW", data = "https://mail.google.com/mail/?view=cm&to=a@b.c"
    )
    private val telegram = AppAtlasEntry(
        packageName = "org.example.messenger", label = "Messenger", versionCode = 1,
        actions = listOf(open, tgLink, waLink, anyHttps, storedShare, gmailShortcut)
    )

    private fun refusedReason(v: ActionGuard.Verdict): String {
        assertThat(v).isInstanceOf(ActionGuard.Verdict.Refused::class.java)
        return (v as ActionGuard.Verdict.Refused).reason
    }

    @Test
    fun `app_open_action refuses compose uris with a hint to the confirmed tools`() {
        val r1 = refusedReason(ActionGuard.check(telegram, tgLink, "https://t.me/share/url?text=hello", null))
        assertThat(r1).contains("send_sms")
        assertThat(r1).contains("draft")
        refusedReason(ActionGuard.check(telegram, waLink, "https://api.whatsapp.com/send?phone=1&text=hi", null))
        refusedReason(ActionGuard.check(telegram, anyHttps, "https://wa.me/1?text=hi", null))
        refusedReason(ActionGuard.check(telegram, anyHttps, "https://mail.google.com/mail/?view=cm&to=a@b.c", null))
        // Stored compose endpoints / shortcut data are refused too, without a model uri.
        refusedReason(ActionGuard.check(telegram, storedShare, null, null))
        refusedReason(ActionGuard.check(telegram, gmailShortcut, null, null))
    }

    @Test
    fun `plain links of the same hosts still open`() {
        assertThat(ActionGuard.check(telegram, tgLink, "https://t.me/durov", null))
            .isEqualTo(ActionGuard.Verdict.Allowed("https://t.me/durov"))
        assertThat(ActionGuard.check(telegram, anyHttps, "https://example.com/news", null))
            .isInstanceOf(ActionGuard.Verdict.Allowed::class.java)
        assertThat(ActionGuard.check(telegram, open, null, null)).isInstanceOf(ActionGuard.Verdict.Allowed::class.java)
    }
}
