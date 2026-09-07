package com.lchuang.xiaozhimobile.extensions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginManagerTest {
    private lateinit var root: java.nio.file.Path
    private lateinit var runtime: RecordingExtensionRuntimeRegistry
    private lateinit var manager: PluginManager

    @Before
    fun setUp() {
        root = Files.createTempDirectory("xzpack-repository-")
        runtime = RecordingExtensionRuntimeRegistry()
        manager = PluginManager(
            repository = ExtensionRepository(root.toFile()),
            runtimeRegistry = runtime,
        )
    }

    @After
    fun tearDown() {
        deleteRecursively(root)
    }

    @Test
    fun importIsDisabledByDefaultAndRequiresDeclaredPermissionReview() {
        val result = manager.importPackage(ByteArrayInputStream(packageBytes(listOf("network"))))

        assertTrue(result is PluginManagerResult.NeedsPermissionConfirmation)
        val pending = result as PluginManagerResult.NeedsPermissionConfirmation
        assertEquals(setOf(ExtensionPermission.NETWORK), pending.requiredPermissions)
        assertFalse(pending.extension.enabled)
        assertFalse(runtime.registeredIds.contains("demo.safe"))
        assertTrue(Files.exists(root.resolve("demo.safe/package.xzpack")))
        assertFalse(Files.exists(root.resolve("skills/demo/SKILL.md")))
    }

    @Test
    fun enableRejectsAnyPermissionSetThatWasNotExplicitlyConfirmed() {
        manager.importPackage(ByteArrayInputStream(packageBytes(listOf("network"))))

        val rejected = manager.enable("demo.safe", emptySet())

        assertEquals(
            PluginManagerResult.Rejected(PluginManagerCode.PERMISSION_CONFIRMATION_REQUIRED),
            rejected,
        )
        assertFalse(runtime.registeredIds.contains("demo.safe"))

        val enabled = manager.enable("demo.safe", setOf(ExtensionPermission.NETWORK))
        assertTrue(enabled is PluginManagerResult.Enabled)
        assertTrue(runtime.registeredIds.contains("demo.safe"))
    }

    @Test
    fun updateWithAddedPermissionDisablesOldRuntimeUntilNewConfirmation() {
        manager.importPackage(ByteArrayInputStream(packageBytes(listOf("network"))))
        manager.enable("demo.safe", setOf(ExtensionPermission.NETWORK))

        val update = manager.importPackage(
            ByteArrayInputStream(packageBytes(listOf("network", "files"), version = "1.1.0")),
        )

        assertTrue(update is PluginManagerResult.NeedsPermissionConfirmation)
        val pending = update as PluginManagerResult.NeedsPermissionConfirmation
        assertEquals(setOf(ExtensionPermission.FILES), pending.addedPermissions)
        assertFalse(pending.extension.enabled)
        assertFalse(runtime.registeredIds.contains("demo.safe"))
        assertEquals(
            PluginManagerResult.Rejected(PluginManagerCode.PERMISSION_CONFIRMATION_REQUIRED),
            manager.enable("demo.safe", setOf(ExtensionPermission.NETWORK)),
        )
        assertTrue(manager.enable("demo.safe", setOf(ExtensionPermission.NETWORK, ExtensionPermission.FILES)) is PluginManagerResult.Enabled)
    }

    @Test
    fun disableAndRemoveAlwaysClearRuntimeRegistration() {
        manager.importPackage(ByteArrayInputStream(packageBytes(emptyList())))
        manager.enable("demo.safe", emptySet())
        assertTrue(runtime.registeredIds.contains("demo.safe"))

        assertTrue(manager.disable("demo.safe") is PluginManagerResult.Disabled)
        assertFalse(runtime.registeredIds.contains("demo.safe"))
        manager.enable("demo.safe", emptySet())

        assertTrue(manager.remove("demo.safe") is PluginManagerResult.Removed)
        assertFalse(runtime.registeredIds.contains("demo.safe"))
        assertFalse(Files.exists(root.resolve("demo.safe")))
    }

    @Test
    fun enabledStateSurvivesManagerReloadOnlyWhenConfirmationMatchesManifest() {
        manager.importPackage(ByteArrayInputStream(packageBytes(listOf("network"))))
        manager.enable("demo.safe", setOf(ExtensionPermission.NETWORK))

        val reloadedRuntime = RecordingExtensionRuntimeRegistry()
        val reloaded = PluginManager(
            repository = ExtensionRepository(root.toFile()),
            runtimeRegistry = reloadedRuntime,
        )

        assertTrue(reloaded.find("demo.safe")?.enabled == true)
        assertTrue(reloadedRuntime.registeredIds.contains("demo.safe"))

        reloaded.disable("demo.safe")
        val disabledRuntime = RecordingExtensionRuntimeRegistry()
        val afterDisable = PluginManager(
            repository = ExtensionRepository(root.toFile()),
            runtimeRegistry = disabledRuntime,
        )
        assertFalse(afterDisable.find("demo.safe")?.enabled == true)
        assertFalse(disabledRuntime.registeredIds.contains("demo.safe"))
    }

    @Test
    fun malformedOrUnsafePackageIsNotStored() {
        val result = manager.importPackage(
            ByteArrayInputStream(
                zip(
                    "manifest.json" to validManifest(files = emptyMap()),
                    "../outside.txt" to "escape",
                ),
            ),
        )

        assertEquals(PluginManagerCode.PACKAGE_INVALID, (result as PluginManagerResult.Rejected).code)
        assertFalse(Files.exists(root.resolve("demo.safe")))
    }

    private fun packageBytes(
        permissions: List<String>,
        version: String = "1.0.0",
    ): ByteArray {
        val skill = "# Declarative skill\n"
        return zip(
            "manifest.json" to validManifest(
                permissions = permissions,
                version = version,
                files = mapOf("skills/demo/SKILL.md" to sha256(skill)),
            ),
            "skills/demo/SKILL.md" to skill,
        )
    }

    private fun validManifest(
        permissions: List<String> = emptyList(),
        version: String = "1.0.0",
        files: Map<String, String>,
    ): String {
        val permissionsJson = permissions.joinToString(",") { "\"$it\"" }
        val filesJson = files.entries.joinToString(",") { (path, sha) -> "\"$path\":\"$sha\"" }
        return "{" +
            "\"id\":\"demo.safe\",\"version\":\"$version\",\"name\":\"Demo\"," +
            "\"permissions\":[$permissionsJson],\"files\":{$filesJson}" +
            "}"
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            entries.forEach { (name, content) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(content.toByteArray(StandardCharsets.UTF_8))
                archive.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun deleteRecursively(path: java.nio.file.Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private class RecordingExtensionRuntimeRegistry : ExtensionRuntimeRegistry {
        val registeredIds = linkedSetOf<String>()

        override fun register(manifest: ExtensionManifest) {
            registeredIds += manifest.id
        }

        override fun unregister(extensionId: String) {
            registeredIds -= extensionId
        }
    }
}
