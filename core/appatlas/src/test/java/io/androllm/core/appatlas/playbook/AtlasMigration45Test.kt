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
 * Branch R1: v4 → v5 of app_atlas.db (playbooks.staleReason), on real SQLite,
 * checked against the schema Room generated for v5 (schemas/…/5.json).
 */
class AtlasMigration45Test {

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
        // A v4 database (every table) with one stored, stale playbook.
        db.createStatement().use { st ->
            for (e in entities(schema(4))) {
                st.execute(createSql(e))
                for (idx in e["indices"]?.jsonArray.orEmpty()) {
                    st.execute(idx.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
                }
            }
            st.execute("INSERT INTO playbooks VALUES ('com.example.video/search','com.example.video','search',1,2,0,'{}','STALE',150,3,1,0,0,'jarvis',2)")
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `migrated v4 database matches Room's v5 schema and keeps the row state`() {
        val v5 = schema(5)
        assertThat(v5["version"]!!.jsonPrimitive.int).isEqualTo(5)
        assertThat(AtlasMigrations.MIGRATION_4_5.startVersion).isEqualTo(4)
        assertThat(AtlasMigrations.MIGRATION_4_5.endVersion).isEqualTo(5)
        db.createStatement().use { st -> AtlasMigrations.SQL_4_5.forEach { st.execute(it) } }

        for (e in entities(v5)) {
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
            st.executeQuery("SELECT status, flaggedVersion, verifiedCount, staleReason FROM playbooks").use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString(1)).isEqualTo("STALE")
                assertThat(rs.getLong(2)).isEqualTo(150L)
                assertThat(rs.getInt(3)).isEqualTo(2)
                // Existing stale rows keep the branch N meaning (replay mismatch), not app_updated.
                assertThat(rs.getString(4)).isNull()
            }
        }
    }
}
