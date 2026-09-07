package com.lchuang.xiaozhimobile.artifacts

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Private-first artifact metadata repository. The Context constructor uses a
 * private SQLite metadata DB; the workspace constructor keeps pure Kotlin
 * tests and non-Android callers deterministic.
 */
class ArtifactRepository private constructor(
    private val workspace: ArtifactWorkspace,
    private val metadataStore: ArtifactMetadataStore,
) {
    constructor(workspace: ArtifactWorkspace) : this(workspace, InMemoryArtifactMetadataStore())

    constructor(
        context: Context,
        workspace: ArtifactWorkspace = ArtifactWorkspace.inAppPrivateStorage(context),
    ) : this(workspace, SqliteArtifactMetadataStore(context.applicationContext))

    fun save(artifact: Artifact): Artifact {
        val privateFile = File(artifact.privatePath).canonicalFile
        require(workspace.isPrivate(privateFile)) { "artifact must remain in app-private workspace" }
        require(privateFile.isFile) { "artifact content file is missing" }
        require(privateFile.length() == artifact.size) { "artifact size does not match content" }
        val digest = ArtifactDigest.sha256(privateFile)
        require(digest.equals(artifact.sha256, ignoreCase = true)) { "artifact digest does not match content" }
        val parentVersion = artifact.parentVersion ?: artifact.version.takeIf { it > 1 }?.minus(1)
        val normalized = artifact.copy(
            privatePath = privateFile.path,
            sha256 = artifact.sha256.lowercase(),
            parentVersion = parentVersion,
        )
        val versionValue = ArtifactVersion.fromArtifact(normalized)
        val existingVersion = metadataStore.findVersion(normalized.artifactId, normalized.version)
        if (existingVersion != null) {
            require(existingVersion == versionValue) { "artifact versions are immutable" }
            return normalized
        }
        if (normalized.version > 1) {
            require(normalized.parentVersion == normalized.version - 1) {
                "artifact version lineage is incomplete"
            }
            require(metadataStore.findVersion(normalized.artifactId, normalized.parentVersion) != null) {
                "artifact parent version is missing"
            }
        }
        metadataStore.saveArtifactWithVersion(normalized, versionValue)
        return normalized
    }

    fun registerCompleted(
        sessionId: String,
        mimeType: String,
        displayName: String,
        privateFile: File,
        sourceAgent: String,
        artifactId: String = "artifact-${UUID.randomUUID()}",
        createdAt: Long = System.currentTimeMillis(),
        version: Int = 1,
        parentVersion: Int? = null,
    ): Artifact {
        require(privateFile.isFile) { "completed artifact file is missing" }
        return save(
            Artifact(
                artifactId = artifactId,
                sessionId = sessionId,
                mimeType = mimeType,
                displayName = displayName,
                privatePath = privateFile.canonicalPath,
                size = privateFile.length(),
                sha256 = ArtifactDigest.sha256(privateFile),
                createdAt = createdAt,
                sourceAgent = sourceAgent,
                version = version,
                status = ArtifactStatus.COMPLETED,
                parentVersion = parentVersion,
            ),
        )
    }

    /** Copies a user-selected external file into private storage first. */
    fun importExternalFile(
        sourceFile: File,
        sessionId: String,
        mimeType: String,
        displayName: String,
        sourceAgent: String,
        artifactId: String = "artifact-${UUID.randomUUID()}",
        createdAt: Long = System.currentTimeMillis(),
        version: Int = 1,
        parentVersion: Int? = null,
        maxBytes: Long = ArtifactDigest.MAX_ARTIFACT_BYTES,
    ): Artifact {
        require(sourceFile.isFile) { "source file is missing" }
        require(maxBytes > 0L && sourceFile.length() <= maxBytes) { "source file exceeds the size limit" }
        val extension = sourceFile.extension.ifBlank { "bin" }
        val staged = workspace.createTempFile(artifactId)
        var destination: File? = null
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(staged).use { output -> copyBounded(input, output, maxBytes) }
            }
            destination = workspace.allocateVersionFile(artifactId, version, extension)
            workspace.moveIntoWorkspace(staged, destination)
            return registerCompleted(
                sessionId = sessionId,
                mimeType = mimeType,
                displayName = displayName,
                privateFile = destination,
                sourceAgent = sourceAgent,
                artifactId = artifactId,
                createdAt = createdAt,
                version = version,
                parentVersion = parentVersion,
            )
        } catch (error: RuntimeException) {
            staged.delete()
            destination?.delete()
            throw error
        } catch (error: java.io.IOException) {
            staged.delete()
            destination?.delete()
            throw IllegalArgumentException("unable to import private artifact", error)
        }
    }

    fun find(artifactId: String): Artifact? = metadataStore.findArtifact(artifactId)

    fun list(): List<Artifact> = metadataStore.listArtifacts()

    fun versions(artifactId: String): List<ArtifactVersion> = metadataStore.listVersions(artifactId)

    fun remove(artifactId: String): Boolean {
        val removed = metadataStore.remove(artifactId)
        if (!removed) return false
        return workspace.deleteArtifactDirectory(artifactId)
    }

    fun close() = metadataStore.close()

    private fun copyBounded(input: InputStream, output: FileOutputStream, maxBytes: Long) {
        require(maxBytes > 0L) { "maxBytes must be positive" }
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "artifact exceeds the size limit" }
            output.write(buffer, 0, count)
        }
        output.flush()
    }

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
        fun inAppPrivateStorage(context: Context): ArtifactRepository = ArtifactRepository(context)
    }
}

private interface ArtifactMetadataStore {
    fun saveArtifactWithVersion(artifact: Artifact, version: ArtifactVersion)
    fun findArtifact(artifactId: String): Artifact?
    fun listArtifacts(): List<Artifact>
    fun remove(artifactId: String): Boolean
    fun findVersion(artifactId: String, version: Int): ArtifactVersion?
    fun listVersions(artifactId: String): List<ArtifactVersion>
    fun close()
}

private class InMemoryArtifactMetadataStore : ArtifactMetadataStore {
    private val artifacts = linkedMapOf<String, Artifact>()
    private val versions = linkedMapOf<Pair<String, Int>, ArtifactVersion>()

    override fun saveArtifactWithVersion(artifact: Artifact, version: ArtifactVersion) {
        val key = version.artifactId to version.version
        require(key !in versions) { "artifact version already exists" }
        versions[key] = version
        artifacts[artifact.artifactId] = artifact
    }

    override fun findArtifact(artifactId: String): Artifact? = artifacts[artifactId]

    override fun listArtifacts(): List<Artifact> = artifacts.values.sortedBy { it.createdAt }

    override fun remove(artifactId: String): Boolean {
        val removed = artifacts.remove(artifactId) != null
        versions.keys.filter { it.first == artifactId }.toList().forEach(versions::remove)
        return removed
    }

    override fun findVersion(artifactId: String, version: Int): ArtifactVersion? = versions[artifactId to version]

    override fun listVersions(artifactId: String): List<ArtifactVersion> = versions.values
        .filter { it.artifactId == artifactId }
        .sortedBy { it.version }

    override fun close() = Unit
}

private class SqliteArtifactMetadataStore(context: Context) : ArtifactMetadataStore {
    private val database = ArtifactMetadataDatabase(context)

    override fun saveArtifactWithVersion(artifact: Artifact, version: ArtifactVersion) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val values = artifactValues(artifact)
            if (db.update("artifacts", values, "artifact_id = ?", arrayOf(artifact.artifactId)) == 0) {
                db.insertOrThrow("artifacts", null, values)
            }
            val versionValues = ContentValues().apply {
                put("artifact_id", version.artifactId)
                put("version", version.version)
                put("private_path", version.privatePath)
                put("size", version.size)
                put("sha256", version.sha256)
                put("created_at", version.createdAt)
                put("source_agent", version.sourceAgent)
                if (version.parentVersion == null) putNull("parent_version") else put("parent_version", version.parentVersion)
                put("mime_type", version.mimeType)
                put("display_name", version.displayName)
            }
            db.insertOrThrow("artifact_versions", null, versionValues)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun artifactValues(artifact: Artifact): ContentValues = ContentValues().apply {
            put("artifact_id", artifact.artifactId)
            put("session_id", artifact.sessionId)
            put("mime_type", artifact.mimeType)
            put("display_name", artifact.displayName)
            put("private_path", artifact.privatePath)
            put("size", artifact.size)
            put("sha256", artifact.sha256)
            put("created_at", artifact.createdAt)
            put("source_agent", artifact.sourceAgent)
            put("version", artifact.version)
            put("status", artifact.status.name)
            if (artifact.parentVersion == null) putNull("parent_version") else put("parent_version", artifact.parentVersion)
    }

    override fun findArtifact(artifactId: String): Artifact? = queryArtifact(
        database.readableDatabase.query(
            "artifacts",
            ARTIFACT_COLUMNS,
            "artifact_id = ?",
            arrayOf(artifactId),
            null,
            null,
            null,
        ),
    )

    override fun listArtifacts(): List<Artifact> {
        val cursor = database.readableDatabase.query(
            "artifacts",
            ARTIFACT_COLUMNS,
            null,
            null,
            null,
            null,
            "created_at ASC, artifact_id ASC",
        )
        return cursor.use { result -> buildList { while (result.moveToNext()) add(readArtifact(result)) } }
    }

    override fun remove(artifactId: String): Boolean {
        val db = database.writableDatabase
        db.beginTransaction()
        return try {
            db.delete("artifact_versions", "artifact_id = ?", arrayOf(artifactId))
            val removed = db.delete("artifacts", "artifact_id = ?", arrayOf(artifactId)) > 0
            db.setTransactionSuccessful()
            removed
        } finally {
            db.endTransaction()
        }
    }

    override fun findVersion(artifactId: String, version: Int): ArtifactVersion? {
        val cursor = database.readableDatabase.query(
            "artifact_versions",
            VERSION_COLUMNS,
            "artifact_id = ? AND version = ?",
            arrayOf(artifactId, version.toString()),
            null,
            null,
            null,
        )
        return cursor.use { result -> if (result.moveToFirst()) readVersion(result) else null }
    }

    override fun listVersions(artifactId: String): List<ArtifactVersion> {
        val cursor = database.readableDatabase.query(
            "artifact_versions",
            VERSION_COLUMNS,
            "artifact_id = ?",
            arrayOf(artifactId),
            null,
            null,
            "version ASC",
        )
        return cursor.use { result -> buildList { while (result.moveToNext()) add(readVersion(result)) } }
    }

    override fun close() = database.close()

    private fun queryArtifact(cursor: Cursor): Artifact? = cursor.use { result ->
        if (result.moveToFirst()) readArtifact(result) else null
    }

    private fun readArtifact(cursor: Cursor): Artifact = Artifact(
        artifactId = cursor.getString(0),
        sessionId = cursor.getString(1),
        mimeType = cursor.getString(2),
        displayName = cursor.getString(3),
        privatePath = cursor.getString(4),
        size = cursor.getLong(5),
        sha256 = cursor.getString(6),
        createdAt = cursor.getLong(7),
        sourceAgent = cursor.getString(8),
        version = cursor.getInt(9),
        status = runCatching { ArtifactStatus.valueOf(cursor.getString(10)) }.getOrDefault(ArtifactStatus.FAILED),
        parentVersion = if (cursor.isNull(11)) null else cursor.getInt(11),
    )

    private fun readVersion(cursor: Cursor): ArtifactVersion = ArtifactVersion(
        artifactId = cursor.getString(0),
        version = cursor.getInt(1),
        privatePath = cursor.getString(2),
        size = cursor.getLong(3),
        sha256 = cursor.getString(4),
        createdAt = cursor.getLong(5),
        sourceAgent = cursor.getString(6),
        parentVersion = if (cursor.isNull(7)) null else cursor.getInt(7),
        mimeType = cursor.getString(8),
        displayName = cursor.getString(9),
    )

    companion object {
        private val ARTIFACT_COLUMNS = arrayOf(
            "artifact_id", "session_id", "mime_type", "display_name", "private_path",
            "size", "sha256", "created_at", "source_agent", "version", "status", "parent_version",
        )
        private val VERSION_COLUMNS = arrayOf(
            "artifact_id", "version", "private_path", "size", "sha256", "created_at", "source_agent",
            "parent_version", "mime_type", "display_name",
        )
    }
}

private class ArtifactMetadataDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    VERSION,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_ARTIFACTS)
        db.execSQL(CREATE_VERSIONS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    companion object {
        private const val DB_NAME = "xiaozhi_artifacts.db"
        private const val VERSION = 1
        private const val CREATE_ARTIFACTS = """
            CREATE TABLE artifacts (
                artifact_id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                display_name TEXT NOT NULL,
                private_path TEXT NOT NULL,
                size INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                source_agent TEXT NOT NULL,
                version INTEGER NOT NULL,
                status TEXT NOT NULL,
                parent_version INTEGER
            )
        """
        private const val CREATE_VERSIONS = """
            CREATE TABLE artifact_versions (
                artifact_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                private_path TEXT NOT NULL,
                size INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                source_agent TEXT NOT NULL,
                parent_version INTEGER,
                mime_type TEXT NOT NULL,
                display_name TEXT NOT NULL,
                PRIMARY KEY (artifact_id, version),
                FOREIGN KEY (artifact_id) REFERENCES artifacts(artifact_id)
            )
        """
    }
}
