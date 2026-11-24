package com.example.security

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.EncryptedTokenStore
import com.example.data.local.WorkItemLocalStore
import com.example.data.model.CachedOrganizationSnapshot
import com.example.data.model.OrganizationAccount
import com.example.data.model.WorkItem
import com.example.data.repository.AzureAccountRepository
import com.example.data.repository.AzureWorkItemParser
import com.example.di.AppContainer
import com.example.ui.viewmodel.ConnectionViewModel
import com.example.ui.viewmodel.WorkItemsViewModel
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Regression tests for the Phase 1 security fixes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityHardeningTest {

    private lateinit var application: Application
    private lateinit var container: AppContainer

    private val cachedItem = WorkItem(
        id = 777,
        title = "Confidential roadmap item",
        workItemType = "Feature",
        state = "Active",
        teamProject = "CorePlatform"
    )

    @Before
    fun setUp() {
        AppContainer.resetForTesting()
        application = ApplicationProvider.getApplicationContext()
        container = AppContainer.getInstance(application)
        container.repository.clearCredentials()
    }

    @After
    fun tearDown() {
        container.repository.clearCredentials()
        AppContainer.resetForTesting()
    }

    // ---------------------------------------------------------------------------------------
    // 1.1 The offline cache is only revealed to a credential that already unlocked the org.
    //
    // The cache is read on the IO dispatcher and applied on the main thread, so the tests run
    // the main looper while waiting. They then disconnect to cancel the pending network call.
    // ---------------------------------------------------------------------------------------

    /** Runs the main looper until [condition] holds or [timeoutMs] passes; returns the result. */
    private fun runMainLooperUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    @Test
    fun `unknown PAT for a cached organization does not reveal cached work items`() {
        val factory = container.provideViewModelFactory(application)
        val connectionViewModel = factory.create(ConnectionViewModel::class.java)
        val workItemsViewModel = factory.create(WorkItemsViewModel::class.java)
        runBlocking { container.repository.saveCachedOrganizationSnapshot("contoso", listOf(cachedItem)) }

        connectionViewModel.connect("contoso", "not-the-real-token", rememberCredentials = false)

        // Give a (wrong) cache restore ample time to show up; it never should.
        val leaked = runMainLooperUntil(timeoutMs = 1_000) {
            workItemsViewModel.allWorkItems.value.any { it.id == cachedItem.id }
        }
        assertFalse("Cached work items must not be shown before Azure DevOps accepts the token", leaked)
        connectionViewModel.disconnect()
    }

    @Test
    fun `known PAT still restores the offline cache instantly`() {
        val factory = container.provideViewModelFactory(application)
        val connectionViewModel = factory.create(ConnectionViewModel::class.java)
        val workItemsViewModel = factory.create(WorkItemsViewModel::class.java)
        container.sessionManager.updateOrganizations(
            listOf(OrganizationAccount("contoso", "the-real-token", isRemembered = true))
        )
        runBlocking { container.repository.saveCachedOrganizationSnapshot("contoso", listOf(cachedItem)) }

        // Blank token = "use the saved token" (the UI never holds the stored token).
        connectionViewModel.connect("contoso", "", rememberCredentials = true)

        val restored = runMainLooperUntil(timeoutMs = 5_000) {
            workItemsViewModel.allWorkItems.value.any { it.id == cachedItem.id }
        }
        assertTrue("The saved account's offline cache should be restored", restored)
        connectionViewModel.disconnect()
    }

    // ---------------------------------------------------------------------------------------
    // Cache purge for organizations that are not saved accounts
    // ---------------------------------------------------------------------------------------

    @Test
    fun `clearCachesExcept deletes only files of organizations that are not kept`() {
        val store = WorkItemLocalStore(application)
        runBlocking {
            store.saveSnapshot(CachedOrganizationSnapshot(organization = "keepme", workItems = listOf(cachedItem)))
            store.saveSnapshot(CachedOrganizationSnapshot(organization = "dropme", workItems = listOf(cachedItem)))
        }
        val cacheDir = File(application.filesDir, "azure_work_items_cache")
        assertTrue(File(cacheDir, "snapshot_keepme.json").exists())
        assertTrue(File(cacheDir, "snapshot_dropme.json").exists())

        store.clearCachesExcept(listOf("KeepMe"))

        assertTrue(File(cacheDir, "snapshot_keepme.json").exists())
        assertFalse(File(cacheDir, "snapshot_dropme.json").exists())
        val freshStore = WorkItemLocalStore(application)
        assertNotNull(freshStore.loadSnapshotSync("keepme"))
        assertNull(freshStore.loadSnapshotSync("dropme"))
    }

    // ---------------------------------------------------------------------------------------
    // 1.6 The software fallback key is never used unless explicitly allowed
    // ---------------------------------------------------------------------------------------

    @Test
    fun `token store fails closed when the Keystore is missing and fallback is not allowed`() {
        val strictStore = EncryptedTokenStore(application, allowSoftwareFallback = false)
        assertNull(strictStore.encryptToken("secret-pat"))
        assertNull(strictStore.getOrCreateCacheDataKey())
    }

    @Test
    fun `Robolectric is detected so existing tests keep their software key`() {
        assertTrue(EncryptedTokenStore.isRobolectricEnvironment())
        val encrypted = EncryptedTokenStore(application).encryptToken("secret-pat")
        assertNotNull(encrypted)
        assertEquals("secret-pat", EncryptedTokenStore(application).decryptToken(encrypted!!))
    }

    // ---------------------------------------------------------------------------------------
    // 1.4 Re-sign-in flag
    // ---------------------------------------------------------------------------------------

    @Test
    fun `re-sign-in flag is set per organization and cleared by a successful save`() {
        val accounts = AzureAccountRepository(application, EncryptedTokenStore(application))
        accounts.setReauthRequired("Contoso", true)
        assertTrue(accounts.isReauthRequired("contoso"))
        assertFalse(accounts.isReauthRequired("fabrikam"))

        accounts.saveOrganizationAccount(OrganizationAccount("contoso", "new-token", isRemembered = true))

        assertFalse(accounts.isReauthRequired("contoso"))
    }

    // ---------------------------------------------------------------------------------------
    // 1.7 Small fixes
    // ---------------------------------------------------------------------------------------

    @Test
    fun `OrganizationAccount toString never contains the token`() {
        val text = OrganizationAccount("contoso", "super-secret-pat-value").toString()
        assertFalse(text.contains("super-secret-pat-value"))
        assertTrue(text.contains("contoso"))
    }

    @Test
    fun `plain-text descriptions are escaped before being written to an HTML field`() {
        val typed = "Steps:\n1. <img src=x onerror=alert(1)>\n2. Tom & \"Jerry\""

        val html = AzureWorkItemParser.plainTextToHtml(typed)

        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;img"))
        assertTrue(html.contains("<br>"))
        // Reading it back gives the user exactly what they typed.
        assertEquals(typed, AzureWorkItemParser.cleanHtml(html))
    }
}
