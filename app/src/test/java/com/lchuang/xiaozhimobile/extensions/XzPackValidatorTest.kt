package com.lchuang.xiaozhimobile.extensions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XzPackValidatorTest {
    private val validator = XzPackValidator()

    @Test
    fun acceptsValidDeclarativePackageAndRecordsDigests() {
        val readme = "# Safe demo\n"
        val skill = "# Skill\n"
        val bytes = archive(
            "manifest.json" to manifest(
                files = mapOf(
                    "README.md" to sha256(readme),
                    "skills/demo/SKILL.md" to sha256(skill),
                ),
            ),
            "README.md" to readme,
            "skills/demo/SKILL.md" to skill,
        )

        val result = validator.validate(ByteArrayInputStream(bytes))

        assertTrue(result is XzPackValidationResult.Valid)
        val packageResult = result as XzPackValidationResult.Valid
        assertEquals("demo.safe", packageResult.packageValue.manifest.id)
        assertEquals(3, packageResult.packageValue.entries.size)
        assertEquals(sha256(skill), packageResult.packageValue.entry("skills/demo/SKILL.md")?.sha256)
    }

    @Test
    fun acceptsSafeDirectoryEntriesWithoutTreatingThemAsPayload() {
        val skill = "# Skill\n"
        val bytes = archive(
            "manifest.json" to manifest(files = mapOf("skills/demo/SKILL.md" to sha256(skill))),
            "skills/" to "",
            "skills/demo/" to "",
            "skills/demo/SKILL.md" to skill,
        )

        val result = validator.validate(bytes)

        assertTrue(result is XzPackValidationResult.Valid)
        assertEquals(2, (result as XzPackValidationResult.Valid).packageValue.entries.size)
    }

    @Test
    fun rejectsTraversalBeforeAnyExtractionCanOccur() {
        val bytes = archive(
            "manifest.json" to manifest(),
            "../outside.txt" to "must not escape",
        )

        assertCode(bytes, XzPackValidationCode.PATH_TRAVERSAL)
    }

    @Test
    fun rejectsExecutableOrNativePayloads() {
        listOf("classes.dex", "plugin.jar", "lib.so", "run.sh", "run.bat", "code.ps1", "code.py", "code.js")
            .forEach { forbiddenName ->
                val bytes = archive(
                    "manifest.json" to manifest(),
                    "assets/$forbiddenName" to "not executable",
                )
                assertCode(bytes, XzPackValidationCode.DISALLOWED_FILE_TYPE)
        }
    }

    @Test
    fun rejectsUnknownPayloadExtensionEvenUnderAssets() {
        val bytes = archive(
            "manifest.json" to manifest(files = mapOf("assets/archive.zip" to "0".repeat(64))),
            "assets/archive.zip" to "not a nested package",
        )

        assertCode(bytes, XzPackValidationCode.DISALLOWED_FILE_TYPE)
    }

    @Test
    fun rejectsMalformedManifestEncoding() {
        val bytes = archiveBytes(
            "manifest.json" to byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte()),
        )

        assertCode(bytes, XzPackValidationCode.MANIFEST_INVALID)
    }

    @Test
    fun enforcesStreamingEntryLimitBeforeRetainingPayload() {
        val validatorWithSmallLimit = XzPackValidator(maxEntryBytes = 3)

        val result = validatorWithSmallLimit.validate(
            archive("manifest.json" to manifest()),
        )

        assertTrue(result is XzPackValidationResult.Invalid)
        assertEquals(XzPackValidationCode.ENTRY_TOO_LARGE, (result as XzPackValidationResult.Invalid).code)
    }

    @Test
    fun rejectsDuplicateManifestFileKeys() {
        val duplicateFileManifest = """
            {"id":"demo.safe","version":"1.0.0","name":"Duplicate","files":{"README.md":"${"0".repeat(64)}","README.md":"${"1".repeat(64)}"}}
        """.trimIndent()

        assertCode(
            archive("manifest.json" to duplicateFileManifest),
            XzPackValidationCode.MANIFEST_INVALID,
        )
    }

    @Test
    fun rejectsInvalidVersion() {
        assertCode(
            archive("manifest.json" to manifest(version = "v1")),
            XzPackValidationCode.INVALID_VERSION,
        )
    }

    @Test
    fun rejectsUnknownPermission() {
        assertCode(
            archive("manifest.json" to manifest(permissions = listOf("root"))),
            XzPackValidationCode.UNKNOWN_PERMISSION,
        )
    }

    @Test
    fun rejectsShaMismatch() {
        val bytes = archive(
            "manifest.json" to manifest(files = mapOf("README.md" to "0".repeat(64))),
            "README.md" to "actual content",
        )

        assertCode(bytes, XzPackValidationCode.SHA_MISMATCH)
    }

    @Test
    fun rejectsDuplicateManifestIdKeys() {
        val duplicateIdManifest = """
            {"id":"first.safe","id":"second.safe","version":"1.0.0","name":"Duplicate"}
        """.trimIndent()

        assertCode(
            archive("manifest.json" to duplicateIdManifest),
            XzPackValidationCode.MANIFEST_INVALID,
        )
    }

    private fun assertCode(bytes: ByteArray, expected: XzPackValidationCode) {
        val result = validator.validate(ByteArrayInputStream(bytes))
        assertTrue("expected $expected but got $result", result is XzPackValidationResult.Invalid)
        assertEquals(expected, (result as XzPackValidationResult.Invalid).code)
    }

    private fun manifest(
        version: String = "1.0.0",
        permissions: List<String> = listOf("network"),
        files: Map<String, String> = emptyMap(),
    ): String {
        val permissionsJson = permissions.joinToString(",") { "\"${escapeJson(it)}\"" }
        val filesJson = files.entries.joinToString(",") { (path, sha) ->
            "\"${escapeJson(path)}\":\"${escapeJson(sha)}\""
        }
        return "{" +
            "\"id\":\"demo.safe\",\"version\":\"${escapeJson(version)}\",\"name\":\"Demo\",\"description\":\"Declarative test package\"," +
            "\"permissions\":[$permissionsJson],\"capabilities\":[\"skill\"],\"files\":{$filesJson}" +
            "}"
    }

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        return archiveBytes(*entries.map { (name, content) ->
            name to content.toByteArray(StandardCharsets.UTF_8)
        }.toTypedArray())
    }

    private fun archiveBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(content: String): String = sha256(content.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { byte -> "%02x".format(byte) }
}
