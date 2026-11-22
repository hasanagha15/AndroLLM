package io.androllm.feature.models.catalog

import io.androllm.core.models.catalog.CatalogParser
import io.androllm.engine.compat.ContainerMetadataReader
import io.androllm.engine.compat.ModelCompatibilityResolver
import io.androllm.engine.compat.ModelCompatibilityResolver.DetectionSource
import io.androllm.engine.compat.ModelFamily
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate D, B2: runs the app's real family resolver on the REAL LlmMetadata of
 * the pinned catalog files (fixtures built by
 * core/models/tools/make_litertlm_metadata_fixture.py: container header +
 * LlmMetadataProto section byte-for-byte; all other section entries zeroed).
 *
 * Expected after the fix: Qwen3 containers keep the app's Qwen3 template; the
 * containers that carry their own template (Ministral 3, Falcon-H1, Qwen3.5)
 * resolve to GENERIC, i.e. the container template — never the built-in
 * Mistral v0.3 template (drops the system role) or QWEN2P5 by stop token.
 */
class PinnedModelFamilyResolutionTest {

    private val catalog by lazy {
        CatalogParser.parse(File("../../core/models/src/main/assets/catalog_v1.json").readText())
            .catalog.models.associateBy { it.id }
    }

    private val index by lazy {
        Json.parseToJsonElement(resource("index.json").decodeToString()).jsonObject
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("litertlm-headers/$name")) {
            "missing fixture litertlm-headers/$name"
        }.use { it.readBytes() }

    private fun resolve(id: String): ModelCompatibilityResolver.Resolution {
        val model = requireNotNull(catalog[id]) { "catalog has no $id" }
        val entry = index.getValue(id).jsonObject
        // The fixture must come from the exact pinned file of the catalog entry.
        assertEquals("$id fixture revision", model.revision, entry.getValue("revision").jsonPrimitive.content)
        assertEquals("$id fixture sha256", model.sha256, entry.getValue("sha256").jsonPrimitive.content)
        val bytes = resource(entry.getValue("file").jsonPrimitive.content)
        val contents = ContainerMetadataReader.parse(bytes)
        // And the LlmMetadata section is the untouched original.
        val metaLen = entry.getValue("llmMetadataBytes").jsonPrimitive.int
        val metaSha = MessageDigest.getInstance("SHA-256")
            .digest(bytes.copyOfRange(bytes.size - metaLen, bytes.size))
            .joinToString("") { "%02x".format(it) }
        assertEquals("$id LlmMetadata bytes", entry.getValue("llmMetadataSha256").jsonPrimitive.content, metaSha)
        return ModelCompatibilityResolver.resolve(contents.metadata, model.fileName, model.family)
    }

    @Test
    fun qwen3InstructUsesTheQwen3Family() {
        val r = resolve("litertlm-qwen3-4b-instruct-2507")
        assertEquals(r.reason, ModelFamily.QWEN3, r.family)
        assertEquals(DetectionSource.CONTAINER_MODEL_TYPE, r.source)
        assertEquals(ModelFamily.QWEN3, resolve("litertlm-qwen3-4b").family)
    }

    @Test
    fun ministralUsesItsContainerTemplateNotBuiltInMistral() {
        val r = resolve("litertlm-ministral-3-3b-instruct")
        assertNotEquals(r.reason, ModelFamily.MISTRAL, r.family)
        assertEquals(r.reason, ModelFamily.GENERIC, r.family)
        assertEquals(DetectionSource.CATALOG_FAMILY, r.source)
    }

    @Test
    fun falconUsesItsContainerTemplateNotQwen25ByStopToken() {
        val r = resolve("litertlm-falcon-h1-3b-instruct")
        assertNotEquals(r.reason, ModelFamily.QWEN2P5, r.family)
        assertEquals(r.reason, ModelFamily.GENERIC, r.family)
        assertEquals(DetectionSource.CATALOG_FAMILY, r.source)
    }

    @Test
    fun qwen35UsesItsContainerTemplate() {
        for (id in listOf("litertlm-qwen3.5-2b", "litertlm-qwen3.5-4b-int4")) {
            val r = resolve(id)
            assertEquals("$id: ${r.reason}", ModelFamily.GENERIC, r.family)
            assertTrue("$id: ${r.source}", r.source == DetectionSource.CATALOG_FAMILY)
        }
    }

    @Test
    fun containerTemplatesKeepTheSystemRole() {
        // The GENERIC route only helps if the container template renders a
        // system message; check the real templates carry a system branch.
        for (id in listOf(
            "litertlm-ministral-3-3b-instruct", "litertlm-falcon-h1-3b-instruct",
            "litertlm-qwen3.5-2b", "litertlm-qwen3.5-4b-int4"
        )) {
            val entry = index.getValue(id).jsonObject
            val meta = ContainerMetadataReader.parse(resource(entry.getValue("file").jsonPrimitive.content)).metadata
            val template = meta.jinjaPromptTemplate.orEmpty()
            assertTrue("$id has no embedded template", template.isNotBlank())
            // Either an explicit system branch, or (Qwen3.5-2B) a generic
            // `<|im_start|>{{ message.role }}` turn that renders any role.
            assertTrue(
                "$id template has no system role",
                template.contains("system") || template.contains("{{ message.role }}")
            )
        }
    }
}
