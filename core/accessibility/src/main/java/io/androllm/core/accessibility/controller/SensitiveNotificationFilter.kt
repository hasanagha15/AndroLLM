package io.androllm.core.accessibility.controller

import io.androllm.core.tools.tool.impl.NotificationPrivacyFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * K: read_notifications returns only "notification from <app>" for the apps
 * whose screen is hidden (banking/payment incl. the user's list, Tesla,
 * WhatsApp): balances, amounts and OTP codes never reach the model.
 */
@Singleton
class SensitiveNotificationFilter @Inject constructor(
    private val sensitiveApps: SensitiveAppStore
) : NotificationPrivacyFilter {
    override suspend fun hideContent(packageName: String): Boolean =
        SensitiveAppGuard.screenReadBlockedReason(packageName, sensitiveApps.current()) != null
}
