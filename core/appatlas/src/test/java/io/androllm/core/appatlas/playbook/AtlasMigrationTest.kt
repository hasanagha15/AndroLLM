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
 * v2 → v3 migration of app_atlas.db, run on a real SQLite (sqlite-jdbc) and
 * checked against the schema Room itself generated for v3
 * (core/appatlas/schemas/…/3.json, exportSchema = true) — the same table /
 * column / key / index comparison Room's TableInfo validation does on open.
 */
class AtlasMigrationTest {

    private lateinit var db: Connection
    private lateinit var schema: JsonObject

    private fun entity(table: String): JsonObject =
        schema["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == table }

    private fun createSql(e: JsonObject) = e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content)

    @Before
    fun setUp() {
        val file = File("schemas/io.androllm.core.appatlas.store.AtlasDatabase/3.json")
        assertThat(file.exists()).isTrue()
        schema = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
        assertThat(schema["version"]!!.jsonPrimitive.int).isEqualTo(3)
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // A v2 database: the two pre-existing tables (unchanged in v3) with data.
        db.createStatement().use { st ->
            st.execute(createSql(entity("atlas_apps")))
            st.execute(createSql(entity("learned_paths")))
            st.execute("INSERT INTO atlas_apps VALUES ('com.example.video','Video',150,1,0,'{}',1)")
            st.execute("INSERT INTO learned_paths (packageName,taskKey,versionCode,steps,successCount,verified,stale,updatedAt,confirmStep) VALUES ('com.example.video','search',150,'[]',1,1,0,1,NULL)")
        }
    }

    @After
    fun tearDown() = db.close()

    private fun migrate() = db.createStatement().use { st -> AtlasMigrations.SQL_2_3.forEach { st.execute(it) } }

    @Test
    fun `migration statements equal Room's generated v3 schema`() {
        val expected = listOf(entity("playbooks"), entity("playbook_relearn")).flatMap { e ->
            listOf(createSql(e)) + (e["indices"]?.jsonArray.orEmpty()).map { idx ->
                idx.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content)
            }
        }
        assertThat(AtlasMigrations.SQL_2_3).containsExactlyElementsIn(expected)
        assertThat(AtlasMigrations.MIGRATION_2_3.startVersion).isEqualTo(2)
        assertThat(AtlasMigrations.MIGRATION_2_3.endVersion).isEqualTo(3)
    }

    @Test
    fun `migrated database matches every v3 table and keeps v2 data`() {
        migrate()
        for (e in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
            val table = e["tableName"]!!.jsonPrimitive.content
            val cols = db.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString("name"), Triple(rs.getString("type"), rs.getInt("notnull") == 1, rs.getInt("pk"))) }
                }
            }
            val fields = e["fields"]!!.jsonArray.map { it.jsonObject }
            assertThat(cols.keys).containsExactlyElementsIn(fields.map { it["columnName"]!!.jsonPrimitive.content })
            for (f in fields) {
                val c = cols.getValue(f["columnName"]!!.jsonPrimitive.content)
                assertThat(c.first).isEqualTo(f["affinity"]!!.jsonPrimitive.content)
                assertThat(c.second).isEqualTo(f["notNull"]?.jsonPrimitive?.boolean ?: false) // Room omits notNull=false
            }
            val pk = e["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertThat(cols.filter { it.value.third > 0 }.entries.sortedBy { it.value.third }.map { it.key }).containsExactlyElementsIn(pk).inOrder()
            for (idx in e["indices"]?.jsonArray.orEmpty()) {
                val name = idx.jsonObject["name"]!!.jsonPrimitive.content
                val exists = db.createStatement().use { st ->
                    st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='$name'").use { it.next() }
                }
                assertThat(exists).isTrue()
            }
        }
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM atlas_apps").use { rs -> rs.next(); assertThat(rs.getInt(1)).isEqualTo(1) }
            st.executeQuery("SELECT COUNT(*) FROM learned_paths").use { rs -> rs.next(); assertThat(rs.getInt(1)).isEqualTo(1) }
            st.execute("INSERT INTO playbooks VALUES ('com.example.video/search','com.example.video','search',1,2,0,'{}','OK',NULL,0,0,0,0,'file')")
            st.execute("INSERT INTO playbook_relearn VALUES ('com.example.video/search',150,'com.example.video',0,'expect_timeout',1,0)")
        }
    }

    @Test
    fun `migration is idempotent (IF NOT EXISTS)`() {
        migrate()
        migrate()
    }
}
