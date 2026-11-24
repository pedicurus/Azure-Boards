package com.example.data.local

import android.content.Context
import com.example.data.model.CachedNotificationsState
import com.example.data.model.CachedOrganizationMetadata
import com.example.data.model.CachedOrganizationSnapshot
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides fast, atomic on-device persistence for work items, progressive sync queues,
 * project process/category metadata, and notifications per Azure DevOps organization.
 *
 * Every cache file is encrypted at rest with AES-256-GCM via [LocalCacheCipher]. If no
 * encryption key is available, data is kept in memory only and never written in plaintext.
 * Plaintext files left by earlier app versions are re-encrypted (or deleted) on first read.
 */
class WorkItemLocalStore(
    context: Context,
    tokenStore: EncryptedTokenStore = EncryptedTokenStore(context.applicationContext ?: context)
) {

    private val cacheCipher = LocalCacheCipher(tokenStore)

    private val cacheDir: File = File(context.filesDir, "azure_work_items_cache").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val moshi: Moshi = Moshi.Builder()
        .build()

    private val snapshotAdapter = moshi.adapter(CachedOrganizationSnapshot::class.java)
    private val metadataAdapter = moshi.adapter(CachedOrganizationMetadata::class.java)
    private val notificationsAdapter = moshi.adapter(CachedNotificationsState::class.java)

    private val inMemorySnapshots = ConcurrentHashMap<String, CachedOrganizationSnapshot>()
    private val inMemoryMetadata = ConcurrentHashMap<String, CachedOrganizationMetadata>()
    private val inMemoryNotifications = ConcurrentHashMap<String, CachedNotificationsState>()

    private fun sanitizeOrgKey(organization: String): String {
        return organization.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_")
    }

    private fun snapshotFile(orgKey: String): File =
        File(cacheDir, "snapshot_${orgKey}.json")

    private fun metadataFile(orgKey: String): File =
        File(cacheDir, "metadata_${orgKey}.json")

    private fun notificationsFile(orgKey: String): File =
        File(cacheDir, "notifications_${orgKey}.json")

    /**
     * Reads and decrypts [file]. A legacy plaintext file from an earlier app version is parsed
     * once, then immediately rewritten encrypted, or deleted if it cannot be encrypted.
     */
    private fun <T> readCacheFile(file: File, adapter: JsonAdapter<T>): T? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            if (cacheCipher.isEncryptedFile(file)) {
                val plain = cacheCipher.decryptFile(file, aad = file.name) ?: return null
                adapter.fromJson(Buffer().write(plain))
            } else {
                val parsed = file.source().buffer().use { source -> adapter.fromJson(source) }
                synchronized(FILE_LOCK) {
                    // Re-check under the lock: a concurrent save may already have replaced it.
                    if (file.exists() && !cacheCipher.isEncryptedFile(file)) {
                        if (parsed == null || !writeCacheFileLocked(file, adapter, parsed)) {
                            file.delete()
                        }
                    }
                }
                parsed
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Encrypts and atomically persists [value] to [file]. */
    private fun <T> writeCacheFile(file: File, adapter: JsonAdapter<T>, value: T) {
        synchronized(FILE_LOCK) {
            try {
                if (!cacheDir.exists()) cacheDir.mkdirs()
                if (!writeCacheFileLocked(file, adapter, value)) {
                    // No key: never leave stale (or legacy plaintext) data behind.
                    file.delete()
                }
            } catch (_: Exception) {
                // Ignore disk write failure gracefully
            }
        }
    }

    /** Must be called while holding [FILE_LOCK]. Returns `false` if nothing was written. */
    private fun <T> writeCacheFileLocked(file: File, adapter: JsonAdapter<T>, value: T): Boolean {
        val tempFile = File(file.parentFile, file.nameWithoutExtension + ".tmp")
        if (!writeEncrypted(tempFile, aad = file.name, adapter = adapter, value = value)) {
            tempFile.delete()
            return false
        }
        if (!tempFile.renameTo(file)) {
            val written = writeEncrypted(file, aad = file.name, adapter = adapter, value = value)
            tempFile.delete()
            return written
        }
        return true
    }

    private fun <T> writeEncrypted(target: File, aad: String, adapter: JsonAdapter<T>, value: T): Boolean {
        FileOutputStream(target).use { fileOut ->
            val encryptingOut = cacheCipher.encryptingStream(fileOut, aad) ?: return false
            encryptingOut.sink().buffer().use { sink -> adapter.toJson(sink, value) }
        }
        return true
    }

    /**
     * Loads the cached work item snapshot for [organization] from memory or local storage.
     */
    fun loadSnapshotSync(organization: String): CachedOrganizationSnapshot? {
        val orgKey = sanitizeOrgKey(organization)
        if (orgKey.isBlank()) return null
        inMemorySnapshots[orgKey]?.let { return it }

        val file = snapshotFile(orgKey)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val parsed = readCacheFile(file, snapshotAdapter)
            if (parsed != null) {
                keepOnlySnapshotInMemory(orgKey, parsed)
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadSnapshot(organization: String): CachedOrganizationSnapshot? =
        withContext(Dispatchers.IO) {
            loadSnapshotSync(organization)
        }

    /**
     * Atomically persists the work item snapshot for [organization] to device storage.
     */
    suspend fun saveSnapshot(snapshot: CachedOrganizationSnapshot) = withContext(Dispatchers.IO) {
        val orgKey = sanitizeOrgKey(snapshot.organization)
        if (orgKey.isBlank()) return@withContext
        keepOnlySnapshotInMemory(orgKey, snapshot)

        writeCacheFile(snapshotFile(orgKey), snapshotAdapter, snapshot)
    }

    /**
     * Loads cached project process hierarchy & category metadata for [organization].
     */
    fun loadMetadataSync(organization: String): CachedOrganizationMetadata? {
        val orgKey = sanitizeOrgKey(organization)
        if (orgKey.isBlank()) return null
        inMemoryMetadata[orgKey]?.let { return it }

        val file = metadataFile(orgKey)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val parsed = readCacheFile(file, metadataAdapter)
            if (parsed != null) {
                inMemoryMetadata[orgKey] = parsed
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadMetadata(organization: String): CachedOrganizationMetadata? =
        withContext(Dispatchers.IO) {
            loadMetadataSync(organization)
        }

    /**
     * Atomically persists project process hierarchy & category metadata for [organization].
     */
    suspend fun saveMetadata(metadata: CachedOrganizationMetadata) = withContext(Dispatchers.IO) {
        val orgKey = sanitizeOrgKey(metadata.organization)
        if (orgKey.isBlank()) return@withContext
        inMemoryMetadata[orgKey] = metadata

        writeCacheFile(metadataFile(orgKey), metadataAdapter, metadata)
    }

    /**
     * Loads cached notifications state for [organization] from memory or local storage.
     */
    fun loadNotificationsStateSync(organization: String): CachedNotificationsState? {
        val orgKey = sanitizeOrgKey(organization)
        if (orgKey.isBlank()) return null
        inMemoryNotifications[orgKey]?.let { return it }

        val file = notificationsFile(orgKey)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val parsed = readCacheFile(file, notificationsAdapter)
            if (parsed != null) {
                inMemoryNotifications[orgKey] = parsed
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadNotificationsState(organization: String): CachedNotificationsState? =
        withContext(Dispatchers.IO) {
            loadNotificationsStateSync(organization)
        }

    /**
     * Atomically persists notifications state for [organization] to device storage.
     */
    suspend fun saveNotificationsState(state: CachedNotificationsState) = withContext(Dispatchers.IO) {
        val orgKey = sanitizeOrgKey(state.organization)
        if (orgKey.isBlank()) return@withContext
        inMemoryNotifications[orgKey] = state

        writeCacheFile(notificationsFile(orgKey), notificationsAdapter, state)
    }

    /**
     * Snapshots hold every cached work item of an organization; keep only the one in use in
     * memory. Others are read back from disk if needed (the files stay the source of truth).
     */
    private fun keepOnlySnapshotInMemory(orgKey: String, snapshot: CachedOrganizationSnapshot) {
        inMemorySnapshots[orgKey] = snapshot
        inMemorySnapshots.keys.retainAll(setOf(orgKey))
    }

    fun clearOrganizationCache(organization: String) {
        val orgKey = sanitizeOrgKey(organization)
        if (orgKey.isBlank()) return
        inMemorySnapshots.remove(orgKey)
        inMemoryMetadata.remove(orgKey)
        inMemoryNotifications.remove(orgKey)
        synchronized(FILE_LOCK) {
            runCatching { snapshotFile(orgKey).delete() }
            runCatching { metadataFile(orgKey).delete() }
            runCatching { notificationsFile(orgKey).delete() }
        }
    }

    /**
     * Deletes the cache files of every organization not listed in [keepOrganizations], such as
     * files left on disk by an earlier session the user chose not to remember. Only files are
     * touched: data loaded in memory during the current session stays usable until it ends.
     */
    fun clearCachesExcept(keepOrganizations: Collection<String>) {
        val keepKeys = keepOrganizations.map { sanitizeOrgKey(it) }.filter { it.isNotBlank() }.toSet()
        synchronized(FILE_LOCK) {
            runCatching {
                cacheDir.listFiles()?.forEach { file ->
                    val match = CACHE_FILE_NAME_REGEX.matchEntire(file.name) ?: return@forEach
                    if (match.groupValues[2] !in keepKeys) {
                        file.delete()
                    }
                }
            }
        }
    }

    fun clearAllCaches() {
        inMemorySnapshots.clear()
        inMemoryMetadata.clear()
        inMemoryNotifications.clear()
        synchronized(FILE_LOCK) {
            runCatching {
                cacheDir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    private companion object {
        /** Process-wide: several repositories each hold their own [WorkItemLocalStore] instance. */
        val FILE_LOCK = Any()

        /** Matches `snapshot_<org>.json`, `metadata_<org>.tmp`, etc.; group 2 is the org key. */
        val CACHE_FILE_NAME_REGEX = Regex("^(snapshot|metadata|notifications)_(.+)\\.(json|tmp)$")
    }
}
