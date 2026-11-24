package com.example.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Plain JVM test: no Android classes involved. */
class RetryOn429InterceptorTest {

    private val server = MockWebServer()
    private val waits = mutableListOf<Long>()
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(RetryOn429Interceptor(sleeper = { waits.add(it) }))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(path: String) = Request.Builder().url(server.url(path)).build()

    @Test
    fun `GET is retried after 429 and honours Retry-After`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        client.newCall(get("/org/_apis/wit/workitems")).execute().use { response ->
            assertEquals(200, response.code)
        }
        assertEquals(2, server.requestCount)
        assertEquals(listOf(3_000L), waits)
    }

    @Test
    fun `gives up after two retries with exponential backoff`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        client.newCall(get("/org/_apis/projects")).execute().use { response ->
            assertEquals(503, response.code)
        }
        assertEquals(3, server.requestCount)
        assertEquals(listOf(1_000L, 2_000L), waits)
    }

    @Test
    fun `Retry-After is capped`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3600"))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(get("/org/_apis/projects")).execute().close()

        assertEquals(listOf(10_000L), waits)
    }

    @Test
    fun `PATCH is never retried`() {
        server.enqueue(MockResponse().setResponseCode(429))

        val patch = Request.Builder()
            .url(server.url("/org/_apis/wit/workitems/1"))
            .patch("[]".toRequestBody("application/json-patch+json".toMediaType()))
            .build()
        client.newCall(patch).execute().use { response ->
            assertEquals(429, response.code)
        }
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `read-only WIQL POST is retried but other POSTs are not`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(429))
        val json = "application/json".toMediaType()

        val wiql = Request.Builder().url(server.url("/org/_apis/wit/wiql")).post("{}".toRequestBody(json)).build()
        client.newCall(wiql).execute().use { assertEquals(200, it.code) }

        val comment = Request.Builder()
            .url(server.url("/org/proj/_apis/wit/workItems/1/comments"))
            .post("{}".toRequestBody(json))
            .build()
        client.newCall(comment).execute().use { assertEquals(429, it.code) }

        assertEquals(3, server.requestCount)
    }
}
