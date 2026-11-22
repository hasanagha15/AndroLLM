package io.androllm.core.tools.prompt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolAdvertisementBudgetTest {

    @Test
    fun `4096-token models get at most a quarter of the window`() {
        val b = ToolAdvertisementBudget.charsFor(4096)
        assertThat(b).isAtMost(4096)
        assertThat(b).isAtLeast(ToolAdvertisementBudget.MIN_BUDGET_CHARS)
        // ~1K tokens of tools leaves > 1.8K tokens for memory, history, question and answer.
        assertThat(4096 - b / ToolAdvertisementBudget.CHARS_PER_TOKEN).isAtLeast(3000)
    }

    @Test
    fun `2048-token models get a smaller budget`() {
        val b = ToolAdvertisementBudget.charsFor(2048)
        assertThat(b).isAtMost(2048)
        assertThat(b).isLessThan(ToolAdvertisementBudget.charsFor(4096))
    }

    @Test
    fun `8192-token models fit the full 11_7K list`() {
        assertThat(ToolAdvertisementBudget.charsFor(8192)).isGreaterThan(11_756)
    }

    @Test
    fun `family cap applies on top`() {
        assertThat(ToolAdvertisementBudget.charsFor(8192, familyCapChars = 4500)).isEqualTo(4500)
        assertThat(ToolAdvertisementBudget.charsFor(4096, familyCapChars = 4500)).isEqualTo(4096)
    }

    @Test
    fun `never returns 0 which would mean unlimited`() {
        for (ctx in listOf(1, 256, 512, 1024)) {
            assertThat(ToolAdvertisementBudget.charsFor(ctx)).isAtLeast(ToolAdvertisementBudget.MIN_BUDGET_CHARS)
        }
        assertThat(ToolAdvertisementBudget.charsFor(4096, familyCapChars = 10)).isAtLeast(ToolAdvertisementBudget.MIN_BUDGET_CHARS)
    }
}
