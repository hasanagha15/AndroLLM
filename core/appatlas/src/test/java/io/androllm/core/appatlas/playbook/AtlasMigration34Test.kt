package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.store.AtlasMigrations
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Branch Q: v3 → v4 of app_atlas.db (playbooks.verifiedCount), on real SQLite,
 * checked against the schema Room generated for v4 (schemas/…/4.json).
 */
class AtlasMigration34Test {

    private lateinit var db: Connection

    private fun schema(version: Int): JsonObject {
        val file = File("schemas/io.androllm.core.appatlas.store.AtlasDatabase/$version.json")
        assertThat(file.exists()).isTrue()
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    private fun entities(s: JsonObject) = s["entities"]!!.jsonArray.map { it.jsonObject }

    private fun createSql(e: JsonObject) =
        e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content)

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // A v3 database (every table) with one stored playbook.
        db.createStatement().use { st ->
            for (e in entities(schema(3))) {
                st.execute(createSql(e))
                for (idx in e["indices"]?.jsonArray.orEmpty()) {
                    st.execute(idx.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
                }
            }
            st.execute("INSERT INTO playbooks VALUES ('com.example.video/search','com.example.video','search',1,2,0,'{}','OK',NULL,3,0,0,0,'jarvis')")
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `migrated v3 database matches Room's v4 schema and keeps rows unverified`() {
        val v4 = schema(4)
        assertThat(v4["version"]!!.jsonPrimitive.int).isEqualTo(4)
        assertThat(AtlasMigrations.MIGRATION_3_4.startVersion).isEqualTo(3)
        assertThat(AtlasMigrations.MIGRATION_3_4.endVersion).isEqualTo(4)
        db.createStatement().use { st -> AtlasMigrations.SQL_3_4.forEach { st.execute(it) } }

        for (e in entities(v4)) {
            val table = e["tableName"]!!.jsonPrimitive.content
            val cols = db.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                    buildMap {
                        while (rs.next()) put(rs.getString("name"), Triple(rs.getString("type"), rs.getInt("notnull") == 1, rs.getString("dflt_value")))
                    }
                }
            }
            val fields = e["fields"]!!.jsonArray.map { it.jsonObject }
            assertThat(cols.keys).containsExactlyElementsIn(fields.map { it["columnName"]!!.jsonPrimitive.content })
            for (f in fields) {
                val c = cols.getValue(f["columnName"]!!.jsonPrimitive.content)
                assertThat(c.first).isEqualTo(f["affinity"]!!.jsonPrimitive.content)
                assertThat(c.second).isEqualTo(f["notNull"]?.jsonPrimitive?.boolean ?: false)
                // Room validates column defaults too.
                f["defaultValue"]?.jsonPrimitive?.content?.let { assertThat(c.third).isEqualTo(it) }
            }
        }
        db.createStatement().use { st ->
            st.executeQuery("SELECT successCount, verifiedCount, source FROM playbooks").use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getInt(1)).isEqualTo(3)
                assertThat(rs.getInt(2)).isEqualTo(0)
                assertThat(rs.getString(3)).isEqualTo("jarvis")
            }
        }
    }
}
