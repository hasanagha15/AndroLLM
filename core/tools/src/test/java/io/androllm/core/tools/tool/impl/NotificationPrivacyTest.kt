package io.androllm.core.tools.tool.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** K N2: protected apps' notifications expose only "notification from <app>" (no OTP / amounts). */
class NotificationPrivacyTest {

    private fun n(pkg: String, title: String, text: String) =
        AndroNotificationListener.ActiveNotification(pkg, title, text, 0L)

    @Test
    fun `hidden app shows only its label, other apps unchanged`() {
        val items = listOf(
            n("az.kapitalbank.mbanking", "Birbank", "OTP 482913 for payment AZN 250.00"),
            n("com.google.android.gm", "New mail", "Meeting at 10")
        )
        val (summary, data) = NotificationTool.format(items, setOf("az.kapitalbank.mbanking")) { "Birbank" }
        assertThat(summary).contains("Notification from Birbank (content hidden")
        assertThat(summary).doesNotContain("482913")
        assertThat(summary).doesNotContain("250.00")
        assertThat(data.toString()).doesNotContain("482913")
        assertThat(summary).contains("Meeting at 10")
    }
}
