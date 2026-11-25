package io.androllm.core.common.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContentLogTest {

    @After
    fun reset() {
        ContentLog.enabled = false
    }

    @Test
    fun `default is off and logs only the length`() {
        assertFalse(ContentLog.enabled)
        assertEquals("[11 chars]", ContentLog.text("hello world"))
        assertEquals("null", ContentLog.text(null))
        assertEquals("[2 error(s)]", ContentLog.errors(listOf("a 'secret'", "b")))
    }

    @Test
    fun `explicit switch includes capped text`() {
        ContentLog.enabled = true
        assertEquals("'hel'", ContentLog.text("hello", 3))
        assertEquals("[a, b]", ContentLog.errors(listOf("a", "b")))
    }

    @Test
    fun `explicit per-call switch ignores the global one`() {
        ContentLog.enabled = true
        assertEquals("[5 chars]", ContentLog.text("hello", 80, include = false))
        ContentLog.enabled = false
        assertEquals("'hello'", ContentLog.text("hello", 80, include = true))
    }
}
