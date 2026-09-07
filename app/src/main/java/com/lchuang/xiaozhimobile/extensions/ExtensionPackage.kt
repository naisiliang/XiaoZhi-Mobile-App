package com.lchuang.xiaozhimobile.extensions

data class ExtensionPackageEntry(
    val path: String,
    val size: Long,
    val sha256: String,
)

data class ExtensionPackage(
    val manifest: ExtensionManifest,
    val entries: List<ExtensionPackageEntry>,
) {
    init {
        require(entries.map { it.path }.distinct().size == entries.size) {
            "extension package entries must be unique"
        }
    }

    fun entry(path: String): ExtensionPackageEntry? {
        val normalized = runCatching { XzPackPath.normalize(path) }.getOrNull() ?: return null
        return entries.firstOrNull { it.path == normalized }
    }
}
