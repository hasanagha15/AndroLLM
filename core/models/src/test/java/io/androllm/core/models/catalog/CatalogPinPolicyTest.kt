package io.androllm.core.models.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPinPolicyTest {

    private val pin = "7c650468cc1598861b1a0deb0ab25be6b20cab13"

    private fun model(
        url: String = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/$pin/qwen3_0_6b_mixed_int4.litertlm",
        repoId: String = "litert-community/Qwen3-0.6B",
        revision: String = pin,
        companionUrl: String = "",
    ) = CatalogModel(
        id = "m",
        name = "M",
        repoId = repoId,
        revision = revision,
        fileName = "qwen3_0_6b_mixed_int4.litertlm",
        downloadUrl = url,
        companionUrl = companionUrl,
    )

    @Test
    fun pinnedCommitUrlIsAccepted() {
        assertNull(CatalogPinPolicy.problem(model()))
        assertTrue(CatalogPinPolicy.isPinned(model()))
    }

    @Test
    fun mutableMainUrlIsRejected() {
        val m = model(
            url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm",
            revision = "main"
        )
        val problem = CatalogPinPolicy.problem(m)
        assertTrue(problem!!.contains("not pinned"))
    }

    @Test
    fun shortOrUppercaseShaIsRejected() {
        assertFalse(CatalogPinPolicy.isPinned(model(url = "https://huggingface.co/a/b/resolve/7c650468/f.litertlm", repoId = "a/b", revision = "7c650468")))
        val upper = pin.uppercase()
        assertFalse(CatalogPinPolicy.isPinned(model(url = "https://huggingface.co/a/b/resolve/$upper/f.litertlm", repoId = "a/b", revision = upper)))
    }

    @Test
    fun revisionFieldMustMatchTheUrlCommit() {
        val m = model(revision = "main")
        assertTrue(CatalogPinPolicy.problem(m)!!.contains("does not match"))
    }

    @Test
    fun repoIdMustMatchTheUrlRepo() {
        val m = model(repoId = "someone-else/Qwen3-0.6B")
        assertTrue(CatalogPinPolicy.problem(m)!!.contains("repoId"))
    }

    @Test
    fun nonHuggingFaceHostIsRejected() {
        val m = model(url = "https://example.com/litert-community/Qwen3-0.6B/resolve/$pin/x.litertlm")
        assertFalse(CatalogPinPolicy.isPinned(m))
    }

    @Test
    fun unpinnedCompanionUrlIsRejected() {
        val m = model(companionUrl = "https://huggingface.co/google/gemma/resolve/main/tokenizer.model")
        assertTrue(CatalogPinPolicy.problem(m)!!.contains("companionUrl"))
    }

    @Test
    fun problemsListsOnlyUnpinnedEntries() {
        val ok = model()
        val bad = model(url = "https://huggingface.co/x/y/resolve/main/f.litertlm", repoId = "x/y", revision = "main")
            .copy(id = "bad")
        val problems = CatalogPinPolicy.problems(listOf(ok, bad))
        assertEquals(1, problems.size)
        assertTrue(problems.single().startsWith("bad:"))
    }

    @Test
    fun validatorReportsUnpinnedAsWarningNotError() {
        val m = CatalogModel(
            id = "w", name = "W", family = "Qwen", architecture = "qwen3", repoId = "t/w",
            fileName = "w.litertlm", downloadUrl = "https://huggingface.co/t/w/resolve/main/w.litertlm",
            quantization = "Q4", version = "1", fileFormat = "LITERTLM", runtimeFormat = "LITERTLM",
            mimeType = "application/x-litertlm", containerType = "qwen3", categories = listOf("CHAT"),
            sizeBytes = 1, parameters = "1B",
        )
        val report = CatalogValidator.validate(listOf(m))
        assertTrue(report.errors.toString(), report.isValid)
        assertTrue(report.warnings.any { it.contains("not pinned") })
    }
}
