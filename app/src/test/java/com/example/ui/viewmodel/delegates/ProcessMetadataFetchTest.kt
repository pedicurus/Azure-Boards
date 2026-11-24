package com.example.ui.viewmodel.delegates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.AzureDevOpsClient
import com.example.data.api.AzureDevOpsService
import com.example.data.local.WorkItemLocalStore
import com.example.data.model.ProjectWorkItemTypeDefinition
import com.example.data.model.ProjectWorkItemTypesResponse
import com.example.data.model.WorkItemStateDefinition
import com.example.data.model.WorkItemStatesResponse
import com.example.data.repository.AzureDevOpsRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Checks that project metadata is fetched once per project and reused while fresh (Phase 2, A2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProcessMetadataFetchTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkItemLocalStore(context).clearAllCaches()
    }

    @After
    fun tearDown() {
        WorkItemLocalStore(context).clearAllCaches()
    }

    /** A fake Azure DevOps server that counts calls per endpoint name. */
    private class FakeServer {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        fun count(endpoint: String): Int = calls[endpoint]?.get() ?: 0
        fun total(): Int = calls.values.sumOf { it.get() }
    }

    private fun installFakeServer(pat: String): FakeServer {
        val server = FakeServer()
        val service = Proxy.newProxyInstance(
            AzureDevOpsService::class.java.classLoader,
            arrayOf(AzureDevOpsService::class.java)
        ) { _, method, _ ->
            server.calls.getOrPut(method.name) { AtomicInteger() }.incrementAndGet()
            when (method.name) {
                "getProjectWorkItemTypes" -> Response.success(
                    ProjectWorkItemTypesResponse(
                        count = 1,
                        value = listOf(
                            ProjectWorkItemTypeDefinition(
                                name = "Task",
                                referenceName = "Microsoft.VSTS.WorkItemTypes.Task",
                                isDisabled = false,
                                states = listOf(
                                    WorkItemStateDefinition(name = "To Do"),
                                    WorkItemStateDefinition(name = "Done")
                                )
                            )
                        )
                    )
                )
                "getProjectWorkItemTypeStates" -> Response.success(WorkItemStatesResponse(count = 0, value = emptyList()))
                else -> Response.error<Any>(404, "{}".toResponseBody("application/json".toMediaType()))
            }
        } as AzureDevOpsService

        val cacheKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest(pat.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val serviceCacheField = AzureDevOpsClient::class.java.getDeclaredField("serviceCache")
        serviceCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = serviceCacheField.get(AzureDevOpsClient) as ConcurrentHashMap<String, AzureDevOpsService>
        cache[cacheKey] = service
        return server
    }

    @Test
    fun `concurrent requests for one project share a single fetch`() = runBlocking {
        val singleServer = installFakeServer("pat-single-fetch")
        WorkItemProcessDelegate(AzureDevOpsRepository(context))
            .fetchAndStoreProjectMetadata("contoso", "Mobile", "pat-single-fetch")
        val oneFetchCalls = singleServer.total()
        assertTrue("A fetch should reach the server", oneFetchCalls > 0)

        val concurrentServer = installFakeServer("pat-concurrent-fetch")
        val delegate = WorkItemProcessDelegate(AzureDevOpsRepository(context))
        coroutineScope {
            repeat(3) {
                launch { delegate.fetchAndStoreProjectMetadata("contoso", "Mobile", "pat-concurrent-fetch") }
            }
        }

        assertEquals(oneFetchCalls, concurrentServer.total())
        assertTrue(delegate.projectProcessMap.value.isNotEmpty())
    }

    @Test
    fun `fresh metadata is reused and force refetches`() = runBlocking {
        val server = installFakeServer("pat-freshness")
        val delegate = WorkItemProcessDelegate(AzureDevOpsRepository(context))

        delegate.fetchAndStoreProjectMetadata("contoso", "Mobile", "pat-freshness")
        val afterFirst = server.total()

        delegate.fetchAndStoreProjectMetadata("contoso", "Mobile", "pat-freshness")
        assertEquals("Fresh metadata must not hit the network again", afterFirst, server.total())

        delegate.fetchAndStoreProjectMetadata("contoso", "Mobile", "pat-freshness", force = true)
        assertTrue("force = true must refetch", server.total() > afterFirst)
    }

    @Test
    fun `fetch times restored from the offline cache skip the network`() = runBlocking {
        val server = installFakeServer("pat-restored")
        val delegate = WorkItemProcessDelegate(AzureDevOpsRepository(context))
        delegate.restoreFetchedAt(mapOf("contoso|mobile" to System.currentTimeMillis()))

        delegate.fetchAndStoreProjectMetadata("contoso", "Mobile", "pat-restored")

        assertEquals(0, server.total())
    }

    @Test
    fun `per-type project state requests are skipped when the type list includes states`() = runBlocking {
        val server = installFakeServer("pat-inline-states")
        WorkItemProcessDelegate(AzureDevOpsRepository(context))
            .fetchAndStoreProjectMetadata("contoso", "Mobile", "pat-inline-states")

        assertEquals(0, server.count("getProjectWorkItemTypeStates"))
    }
}
