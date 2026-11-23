package io.androllm.app.navigation

import io.androllm.core.navigation.Routes

/**
 * Pure start-destination policy for the entry flow (unit-tested in
 * `EntryRoutingTest`).
 *
 * A real Firebase account and a persisted local guest session both count as
 * "has a session": they go to Home (or the one-time Setup screen if it was
 * never completed/skipped). Guest mode only changes WHERE the app starts —
 * it never creates a Firebase identity, so every account/sync feature keeps
 * gating on FirebaseAuth.currentUser exactly as before.
 */
object EntryRouting {

    fun splashTarget(
        signedIn: Boolean,
        guest: Boolean,
        onboardingCompleted: Boolean,
        setupCompleted: Boolean
    ): String = when {
        signedIn || guest -> if (setupCompleted) Routes.HOME else Routes.SETUP
        onboardingCompleted -> Routes.AUTH
        else -> Routes.ONBOARDING
    }

    fun afterOnboarding(signedIn: Boolean, guest: Boolean): String =
        if (signedIn || guest) Routes.HOME else Routes.AUTH
}
