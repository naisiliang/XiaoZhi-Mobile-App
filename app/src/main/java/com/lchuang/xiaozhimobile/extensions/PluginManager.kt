package com.lchuang.xiaozhimobile.extensions

import java.io.InputStream

data class ManagedExtension(
    val manifest: ExtensionManifest,
    val enabled: Boolean,
    val confirmedPermissions: Set<ExtensionPermission>,
) {
    val id: String get() = manifest.id
    val version: String get() = manifest.version
}

interface ExtensionRuntimeRegistry {
    /** Registers declarative metadata only; it never loads package code. */
    fun register(manifest: ExtensionManifest)

    fun unregister(extensionId: String)
}

class InMemoryExtensionRuntimeRegistry : ExtensionRuntimeRegistry {
    private val manifests = linkedMapOf<String, ExtensionManifest>()

    override fun register(manifest: ExtensionManifest) {
        manifests[manifest.id] = manifest
    }

    override fun unregister(extensionId: String) {
        manifests.remove(extensionId)
    }

    fun registeredIds(): Set<String> = manifests.keys.toSet()
}

private object NoOpExtensionRuntimeRegistry : ExtensionRuntimeRegistry {
    override fun register(manifest: ExtensionManifest) = Unit
    override fun unregister(extensionId: String) = Unit
}

enum class PluginManagerCode {
    PACKAGE_INVALID,
    STORAGE_FAILED,
    NOT_FOUND,
    PERMISSION_CONFIRMATION_REQUIRED,
    RUNTIME_STATE_FAILED,
}

sealed interface PluginManagerResult {
    data class ImportedDisabled(val extension: ManagedExtension) : PluginManagerResult

    data class ImportedEnabled(val extension: ManagedExtension) : PluginManagerResult

    data class NeedsPermissionConfirmation(
        val extension: ManagedExtension,
        val requiredPermissions: Set<ExtensionPermission>,
        val addedPermissions: Set<ExtensionPermission> = emptySet(),
    ) : PluginManagerResult

    data class Enabled(val extension: ManagedExtension) : PluginManagerResult

    data class Disabled(val extension: ManagedExtension) : PluginManagerResult

    data class Removed(val extensionId: String) : PluginManagerResult

    data class Rejected(
        val code: PluginManagerCode,
        val detail: String = "",
    ) : PluginManagerResult
}

/** Owns extension lifecycle state while keeping the runtime declarative-only. */
class PluginManager(
    private val repository: ExtensionRepository,
    private val validator: XzPackValidator = XzPackValidator(),
    private val runtimeRegistry: ExtensionRuntimeRegistry = NoOpExtensionRuntimeRegistry,
) {
    private val installedById = linkedMapOf<String, ManagedExtension>()

    init {
        reloadStoredExtensions()
    }

    fun installed(): List<ManagedExtension> = installedById.values.sortedBy { it.id }

    fun find(extensionId: String): ManagedExtension? = installedById[extensionId]

    fun importPackage(input: InputStream): PluginManagerResult {
        var staged: java.io.File? = null
        return try {
            staged = input.use { repository.stage(it) }
            val validation = validator.validate(staged.inputStream())
            val packageValue = when (validation) {
                is XzPackValidationResult.Valid -> validation.packageValue
                is XzPackValidationResult.Invalid -> {
                    return PluginManagerResult.Rejected(
                        PluginManagerCode.PACKAGE_INVALID,
                        validation.code.name,
                    )
                }
            }
            val incomingManifest = packageValue.manifest
            val previous = installedById[incomingManifest.id]
            val addedPermissions = if (previous == null) {
                incomingManifest.permissions
            } else {
                incomingManifest.permissions - previous.manifest.permissions
            }
            val preserveEnabled = previous?.enabled == true && addedPermissions.isEmpty()
            val confirmedPermissions = if (preserveEnabled) {
                previous.confirmedPermissions.intersect(incomingManifest.permissions)
            } else {
                emptySet()
            }
            val stored = repository.install(
                stagedArchive = staged,
                extensionPackage = packageValue,
                enabled = preserveEnabled,
                confirmedPermissions = confirmedPermissions,
            )
            val managed = ManagedExtension(
                manifest = incomingManifest,
                enabled = stored.enabled,
                confirmedPermissions = stored.confirmedPermissions,
            )
            installedById[incomingManifest.id] = managed
            if (managed.enabled) {
                runtimeRegistry.register(managed.manifest)
            } else {
                runtimeRegistry.unregister(managed.id)
            }
            when {
                addedPermissions.isNotEmpty() -> PluginManagerResult.NeedsPermissionConfirmation(
                    extension = managed,
                    requiredPermissions = incomingManifest.permissions,
                    addedPermissions = addedPermissions,
                )
                managed.enabled -> PluginManagerResult.ImportedEnabled(managed)
                incomingManifest.permissions.isNotEmpty() -> PluginManagerResult.NeedsPermissionConfirmation(
                    extension = managed,
                    requiredPermissions = incomingManifest.permissions,
                )
                else -> PluginManagerResult.ImportedDisabled(managed)
            }
        } catch (error: ExtensionStorageException) {
            PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED, error.message.orEmpty())
        } catch (error: java.io.IOException) {
            PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED, error.message.orEmpty())
        } finally {
            staged?.delete()
        }
    }

    fun enable(
        extensionId: String,
        confirmedPermissions: Set<ExtensionPermission>,
    ): PluginManagerResult {
        val current = installedById[extensionId]
            ?: return PluginManagerResult.Rejected(PluginManagerCode.NOT_FOUND)
        if (confirmedPermissions != current.manifest.permissions) {
            return PluginManagerResult.Rejected(PluginManagerCode.PERMISSION_CONFIRMATION_REQUIRED)
        }
        return try {
            repository.updateState(
                id = current.id,
                version = current.version,
                enabled = true,
                confirmedPermissions = confirmedPermissions,
            ) ?: return PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED)
            val enabled = current.copy(enabled = true, confirmedPermissions = confirmedPermissions)
            installedById[extensionId] = enabled
            runtimeRegistry.register(enabled.manifest)
            PluginManagerResult.Enabled(enabled)
        } catch (error: ExtensionStorageException) {
            PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED, error.message.orEmpty())
        }
    }

    fun disable(extensionId: String): PluginManagerResult {
        val current = installedById[extensionId]
            ?: return PluginManagerResult.Rejected(PluginManagerCode.NOT_FOUND)
        return try {
            repository.updateState(
                id = current.id,
                version = current.version,
                enabled = false,
                confirmedPermissions = current.confirmedPermissions,
            ) ?: return PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED)
            val disabled = current.copy(enabled = false)
            installedById[extensionId] = disabled
            runtimeRegistry.unregister(extensionId)
            PluginManagerResult.Disabled(disabled)
        } catch (error: ExtensionStorageException) {
            PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED, error.message.orEmpty())
        }
    }

    fun remove(extensionId: String): PluginManagerResult {
        if (!installedById.containsKey(extensionId)) {
            return PluginManagerResult.Rejected(PluginManagerCode.NOT_FOUND)
        }
        return try {
            if (!repository.remove(extensionId)) {
                return PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED)
            }
            installedById.remove(extensionId)
            runtimeRegistry.unregister(extensionId)
            PluginManagerResult.Removed(extensionId)
        } catch (error: ExtensionStorageException) {
            PluginManagerResult.Rejected(PluginManagerCode.STORAGE_FAILED, error.message.orEmpty())
        }
    }

    private fun reloadStoredExtensions() {
        repository.list().forEach { stored ->
            val validation = runCatching { validator.validate(stored.archiveFile.inputStream()) }.getOrNull()
            val packageValue = validation as? XzPackValidationResult.Valid
            if (packageValue == null ||
                packageValue.packageValue.manifest.id != stored.id ||
                packageValue.packageValue.manifest.version != stored.version
            ) {
                runCatching {
                    repository.updateState(
                        id = stored.id,
                        version = stored.version,
                        enabled = false,
                        confirmedPermissions = emptySet(),
                    )
                }
                runtimeRegistry.unregister(stored.id)
                return@forEach
            }
            val manifest = packageValue.packageValue.manifest
            val confirmed = stored.confirmedPermissions.intersect(manifest.permissions)
            val enabled = stored.enabled && confirmed == manifest.permissions
            val managed = ManagedExtension(manifest, enabled, confirmed)
            installedById[manifest.id] = managed
            if (enabled) runtimeRegistry.register(manifest) else runtimeRegistry.unregister(manifest.id)
        }
    }
}
