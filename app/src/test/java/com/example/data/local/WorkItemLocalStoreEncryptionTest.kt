package com.example.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CachedOrganizationSnapshot
import com.example.data.model.WorkItem
import com.example.di.AppContainer
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkItemLocalStoreEncryptionTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File

    private val secretTitle = "Confidential customer incident Zephyr"
    private val secretEmail = "ada.lovelace@contoso.example"

    @Before
    fun setUp() {
        AppContainer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.filesDir, "azure_work_items_cache")
        WorkItemLocalStore(context).clearAllCaches()
    }

    @After
    fun tearDown() {
        WorkItemLocalStore(context).clearAllCaches()
        EncryptedTokenStore(context).clearFallbackKey()
        AppContainer.resetForTesting()
    }

    private fun snapshot(org: String) = CachedOrganizationSnapshot(
        organization = org,
        workItems = listOf(
            WorkItem(
                id = 4242,
                title = secretTitle,
                workItemType = "Bug",
                state = "Active",
                assignedToName = "Ada Lovelace",
                assignedToEmail = secretEmail,
                description = "Root cause involves customer data",
                teamProject = "CorePlatform"
            )
        ),
        savedAtEpochMs = 1762214400000L
    )

    private fun snapshotFile(org: String) = File(cacheDir, "snapshot_$org.json")

    private fun File.containsText(text: String): Boolean =
        String(readBytes(), Charsets.ISO_8859_1).contains(text)

    @Test
    fun `saved snapshot is encrypted at rest and round-trips across store instances`() = runTest {
        WorkItemLocalStore(context).saveSnapshot(snapshot("contoso-org"))

        val file = snapshotFile("contoso-org")
        assertTrue(file.exists())
        assertFalse("title must not be stored in plaintext", file.containsText(secretTitle))
        assertFalse("email must not be stored in plaintext", file.containsText(secretEmail))
        assertFalse("JSON keys must not be visible", file.containsText("workItems"))

        val loaded = WorkItemLocalStore(context).loadSnapshotSync("contoso-org")
        assertNotNull(loaded)
        assertEquals(secretTitle, loaded!!.workItems.single().title)
        assertEquals(secretEmail, loaded.workItems.single().assignedToEmail)
    }

    @Test
    fun `legacy plaintext snapshot is migrated to encrypted on first read`() = runTest {
        cacheDir.mkdirs()
        val adapter = Moshi.Builder().build().adapter(CachedOrganizationSnapshot::class.java)
        val legacyFile = snapshotFile("legacy-org")
        legacyFile.writeText(adapter.toJson(snapshot("legacy-org")))
        assertTrue(legacyFile.containsText(secretTitle))

        val loaded = WorkItemLocalStore(context).loadSnapshotSync("legacy-org")

        assertEquals(secretTitle, loaded?.workItems?.single()?.title)
        assertTrue(legacyFile.exists())
        assertFalse("legacy file must be rewritten encrypted", legacyFile.containsText(secretTitle))
        assertFalse(legacyFile.containsText(secretEmail))

        val reloaded = WorkItemLocalStore(context).loadSnapshotSync("legacy-org")
        assertEquals(secretTitle, reloaded?.workItems?.single()?.title)
    }

    @Test
    fun `encrypted file copied to another organization's name fails authentication`() = runTest {
        WorkItemLocalStore(context).saveSnapshot(snapshot("org-a"))
        snapshotFile("org-a").copyTo(snapshotFile("org-b"), overwrite = true)

        assertNull(WorkItemLocalStore(context).loadSnapshotSync("org-b"))
        assertNotNull(WorkItemLocalStore(context).loadSnapshotSync("org-a"))
    }

    @Test
    fun `tampered ciphertext is rejected`() = runTest {
        WorkItemLocalStore(context).saveSnapshot(snapshot("contoso-org"))
        val file = snapshotFile("contoso-org")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertNull(WorkItemLocalStore(context).loadSnapshotSync("contoso-org"))
    }

    @Test
    fun `cache written before disconnect cannot be decrypted after the key is cleared`() = runTest {
        WorkItemLocalStore(context).saveSnapshot(snapshot("contoso-org"))
        val leftover = snapshotFile("contoso-org").readBytes()

        EncryptedTokenStore(context).clearFallbackKey()
        // Simulate a leftover file surviving the disconnect.
        snapshotFile("contoso-org").writeBytes(leftover)

        assertNull(WorkItemLocalStore(context).loadSnapshotSync("contoso-org"))
    }
}
