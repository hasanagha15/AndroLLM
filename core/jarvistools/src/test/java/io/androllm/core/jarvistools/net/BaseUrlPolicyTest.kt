package io.androllm.core.jarvistools.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BaseUrlPolicyTest {

    private fun ok(p: BaseUrlPolicy.Parsed) = p is BaseUrlPolicy.Parsed.Ok

    @Test fun teslaHttpsOriginOnly() {
        assertThat(ok(BaseUrlPolicy.parseTesla("https://tesla.lilab.ai"))).isTrue()
        assertThat(ok(BaseUrlPolicy.parseTesla(" https://tesla.lilab.ai/ "))).isTrue()
        for (bad in listOf(
            "http://tesla.lilab.ai",
            "https://tesla.lilab.ai/api/command/charge_port_open?x=",
            "https://tesla.lilab.ai/api/command/open_trunk#",
            "https://tesla.lilab.ai/#",
            "https://tesla.lilab.ai?x=1",
            "https://a:b@tesla.lilab.ai",
            "https://tesla.lilab.ai/x",
            "javascript:alert(1)",
            "",
            null
        )) {
            assertThat(ok(BaseUrlPolicy.parseTesla(bad))).isFalse()
        }
    }

    @Test fun whatsappCleartextOnlyForTailnetHost() {
        assertThat(ok(BaseUrlPolicy.parseWhatsApp("http://pi-vision.example.ts.net:8090"))).isTrue()
        assertThat(ok(BaseUrlPolicy.parseWhatsApp("https://wa.example"))).isTrue()
        assertThat(ok(BaseUrlPolicy.parseWhatsApp("http://192.168.0.10:8090"))).isFalse()
        assertThat(ok(BaseUrlPolicy.parseWhatsApp("http://pi-vision:8090"))).isFalse()
        assertThat(ok(BaseUrlPolicy.parseWhatsApp("http://evil.ts.net:8090"))).isFalse()
    }

    @Test fun originIncludesSchemeHostPort() {
        val p = BaseUrlPolicy.parseWhatsApp("http://pi-vision.example.ts.net:8090") as BaseUrlPolicy.Parsed.Ok
        assertThat(p.origin).isEqualTo("http://pi-vision.example.ts.net:8090")
        val t = BaseUrlPolicy.parseTesla("https://tesla.lilab.ai") as BaseUrlPolicy.Parsed.Ok
        assertThat(t.origin).isEqualTo("https://tesla.lilab.ai:443")
    }
}
