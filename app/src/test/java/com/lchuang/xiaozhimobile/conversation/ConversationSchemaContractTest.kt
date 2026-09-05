package com.lchuang.xiaozhimobile.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

class ConversationSchemaContractTest {
    @Test
    fun `executable schema has exact private conversation tables and columns`() {
        val statements = runCatching {
            Class.forName("com.lchuang.xiaozhimobile.conversation.ConversationDatabase")
                .getField("CREATE_TABLE_STATEMENTS")
                .get(null) as Array<*>
        }.getOrDefault(emptyArray<Any>())
        val actualTables = statements.map { parseCreateTable(it as String) }.toMap()

        assertEquals("xiaozhi_conversations.db", ConversationDatabase.DB_NAME)
        assertEquals(EXPECTED_COLUMNS, actualTables)
        actualTables.values.flatten().forEach { column ->
            FORBIDDEN_COLUMNS.forEach { forbidden -> assertFalse(column == forbidden) }
        }
    }

    @Test
    fun `backup rules exclude conversation database from cloud and device transfer`() {
        val manifest = readMainFile("AndroidManifest.xml")
        val legacyRules = parseMainResource("backup_rules.xml")
        val extractionRules = parseMainResource("data_extraction_rules.xml")

        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))

        val legacyDatabaseRules = elementsByTag(legacyRules, "exclude")
            .filter { it.getAttribute("path") == DATABASE_NAME }
        assertEquals(1, legacyDatabaseRules.size)
        assertDatabaseExclude(legacyDatabaseRules.single())
        assertTrue(elementsByTag(legacyRules, "include").none { it.getAttribute("path") == DATABASE_NAME })

        listOf("cloud-backup", "device-transfer").forEach { sectionName ->
            val sections = directElementsByTag(extractionRules, sectionName)
            assertEquals("expected one $sectionName section", 1, sections.size)
            val databaseRules = elementsByTag(sections.single(), "exclude")
                .filter { it.getAttribute("path") == DATABASE_NAME } +
                elementsByTag(sections.single(), "include")
                    .filter { it.getAttribute("path") == DATABASE_NAME }
            assertEquals("expected one database rule in $sectionName", 1, databaseRules.size)
            assertDatabaseExclude(databaseRules.single())
        }
    }

    private fun parseCreateTable(statement: String): Pair<String, Set<String>> {
        val match = Regex("""(?is)^\s*CREATE\s+TABLE\s+([A-Za-z_][A-Za-z0-9_]*)\s*\((.*)\)\s*$""")
            .matchEntire(statement)
            ?: error("not a CREATE TABLE statement: $statement")
        val columns = splitDefinitions(match.groupValues[2])
            .filterNot { it.trimStart().startsWith("FOREIGN KEY", ignoreCase = true) }
            .map { it.trim().split(Regex("\\s+"), limit = 2).first().lowercase() }
            .toSet()
        return match.groupValues[1] to columns
    }

    private fun splitDefinitions(body: String): List<String> {
        val definitions = mutableListOf<String>()
        var depth = 0
        var start = 0
        body.forEachIndexed { index, character ->
            when (character) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    definitions += body.substring(start, index)
                    start = index + 1
                }
            }
        }
        definitions += body.substring(start)
        return definitions
    }

    private fun parseMainResource(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(findMainResource(name)).documentElement

    private fun findMainResource(name: String): File = listOf(
        File("app/src/main/res/xml/$name"),
        File("src/main/res/xml/$name"),
    ).firstOrNull { it.isFile } ?: error("missing main resource: $name")

    private fun readMainFile(name: String): String = listOf(
        File("app/src/main/$name"),
        File("src/main/$name"),
    ).firstOrNull { it.isFile }?.readText().orEmpty()

    private fun assertDatabaseExclude(element: Element) {
        assertEquals("exclude", element.tagName)
        assertEquals("database", element.getAttribute("domain"))
        assertEquals(DATABASE_NAME, element.getAttribute("path"))
    }

    private fun elementsByTag(parent: Element, tagName: String): List<Element> =
        (0 until parent.getElementsByTagName(tagName).length)
            .map { parent.getElementsByTagName(tagName).item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE }
            .map { it as Element }

    private fun directElementsByTag(parent: Element, tagName: String): List<Element> =
        (0 until parent.childNodes.length)
            .map { parent.childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE && (it as Element).tagName == tagName }
            .map { it as Element }

    companion object {
        private const val DATABASE_NAME = "xiaozhi_conversations.db"

        private val EXPECTED_COLUMNS = linkedMapOf(
            "conversation_sessions" to setOf("id", "title", "started_at", "ended_at", "status", "assistant_name"),
            "conversation_messages" to setOf("id", "session_id", "timestamp", "role", "content", "status"),
        )

        private val FORBIDDEN_COLUMNS = setOf(
            "screenshot",
            "screenshot_blob",
            "accessibility",
            "api_key",
            "password",
            "otp",
            "payment",
            "raw_screen",
            "raw_temporary_screen",
            "temporary_screen",
        )
    }
}
