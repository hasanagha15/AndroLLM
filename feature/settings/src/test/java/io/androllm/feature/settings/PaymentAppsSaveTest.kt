package io.androllm.feature.settings

import io.androllm.core.accessibility.controller.SensitiveAppStore
import io.androllm.core.memory.personal.PersonalContextSettings
import io.androllm.core.memory.personal.PersonalContextStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** K N3/N4: "Save apps" survives a DataStore failure and returns the normalized list. */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentAppsSaveTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(store: SensitiveAppStore): PersonalContextViewModel {
        val personal = mockk<PersonalContextStore>()
        every { personal.settings } returns flowOf(PersonalContextSettings())
        return PersonalContextViewModel(personal, store)
    }

    @Test
    fun `write failure shows not saved and does not crash`() = runTest {
        val store = mockk<SensitiveAppStore>()
        every { store.extras } returns flowOf(emptySet())
        coEvery { store.save(any()) } throws IOException("disk full")
        val model = vm(store)
        model.savePaymentApps("com.example.bank")
        assertTrue(model.paymentAppsStatus.value!!.startsWith("Not saved"))
    }

    @Test
    fun `successful save emits the normalized list`() = runTest {
        val store = mockk<SensitiveAppStore>()
        every { store.extras } returns flowOf(emptySet())
        coEvery { store.save(any()) } returns setOf("com.example.bank")
        val model = vm(store)
        val normalized = async(UnconfinedTestDispatcher(testScheduler)) { model.normalizedPaymentApps.first() }
        model.savePaymentApps("  com.example.bank , not a pkg ")
        assertEquals("com.example.bank", normalized.await())
        assertEquals("Saved (1)", model.paymentAppsStatus.value)
    }
}
