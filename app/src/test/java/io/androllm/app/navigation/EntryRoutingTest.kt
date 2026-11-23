package io.androllm.app.navigation

import io.androllm.core.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryRoutingTest {

    @Test
    fun `persisted guest session skips sign-in on cold start`() {
        assertEquals(
            Routes.HOME,
            EntryRouting.splashTarget(signedIn = false, guest = true, onboardingCompleted = true, setupCompleted = true)
        )
    }

    @Test
    fun `guest who never finished setup sees setup once`() {
        assertEquals(
            Routes.SETUP,
            EntryRouting.splashTarget(signedIn = false, guest = true, onboardingCompleted = true, setupCompleted = false)
        )
    }

    @Test
    fun `no session and onboarding done goes to sign-in`() {
        assertEquals(
            Routes.AUTH,
            EntryRouting.splashTarget(signedIn = false, guest = false, onboardingCompleted = true, setupCompleted = true)
        )
    }

    @Test
    fun `fresh install starts with onboarding`() {
        assertEquals(
            Routes.ONBOARDING,
            EntryRouting.splashTarget(signedIn = false, guest = false, onboardingCompleted = false, setupCompleted = false)
        )
    }

    @Test
    fun `real account routing is unchanged`() {
        assertEquals(
            Routes.HOME,
            EntryRouting.splashTarget(signedIn = true, guest = false, onboardingCompleted = false, setupCompleted = true)
        )
        assertEquals(
            Routes.SETUP,
            EntryRouting.splashTarget(signedIn = true, guest = false, onboardingCompleted = true, setupCompleted = false)
        )
    }

    @Test
    fun `after onboarding a guest goes home, no session goes to sign-in`() {
        assertEquals(Routes.HOME, EntryRouting.afterOnboarding(signedIn = false, guest = true))
        assertEquals(Routes.AUTH, EntryRouting.afterOnboarding(signedIn = false, guest = false))
        assertEquals(Routes.HOME, EntryRouting.afterOnboarding(signedIn = true, guest = false))
    }
}
