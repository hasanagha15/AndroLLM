package io.androllm.core.accessibility.finder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Branch L gate M3: ui_click(id) matches the resource id exactly and checks the label. */
class UiSelectorExactIdTest {

    @Test
    fun `exact id does not match substrings`() {
        val s = UiSelector(resourceId = "send", resourceIdExact = true)
        assertThat(s.resourceIdMatches("com.example.chat:id/send")).isTrue()
        assertThat(s.resourceIdMatches("send")).isTrue()
        assertThat(s.resourceIdMatches("com.example.chat:id/resend_code")).isFalse()
        assertThat(s.resourceIdMatches("com.example.chat:id/send_money_button")).isFalse()
        assertThat(s.resourceIdMatches(null)).isFalse()
        // Legacy substring behaviour is unchanged for other callers.
        assertThat(UiSelector(resourceId = "send").resourceIdMatches("com.example.chat:id/resend_code")).isTrue()
    }

    @Test
    fun `label consistency`() {
        val s = UiSelector(resourceId = "menu_settings", resourceIdExact = true, labelIfPresent = "Settings")
        assertThat(s.labelConsistent("Settings")).isTrue()
        assertThat(s.labelConsistent("")).isTrue() // unlabeled icon button
        assertThat(s.labelConsistent("Delete account")).isFalse()
        assertThat(UiSelector().labelConsistent("anything")).isTrue()
    }
}
