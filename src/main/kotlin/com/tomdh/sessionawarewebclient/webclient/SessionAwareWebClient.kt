package com.tomdh.sessionawarewebclient.webclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A generic stateful web client designed to interface with legacy or scraping-based
 * academic APIs. Provides features like:
 * - Session/Cookie persistence
 * - CSRF token management and background refresh
 * - Concurrency control (ServerBusy)
 * - Transparent redirect handling
 */
abstract class SessionAwareWebClient(
    protected val webClient: WebClient,
    protected val htmlCacheTimeoutMs: Long,
    protected val tokenTimeoutMs: Long,
    protected val tokenRefreshThresholdMs: Long,
    protected val requestTimeoutMs: Long,
    protected val baseUrl: String,
    protected val referer: String,
    protected val origin: String
) {
    private val logger = LoggerFactory.getLogger(SessionAwareWebClient::class.java)

    @Volatile protected var lastToken: String? = null
    @Volatile protected var lastTokenTs: Long = 0
    protected val tokenLock = Mutex()
    @Volatile protected var refreshJob: Job? = null
    protected val refreshScope = CoroutineScope(Dispatchers.IO)

    @Volatile protected var cachedHtml: String? = null
    @Volatile protected var cachedHtmlTs: Long = 0
    protected val htmlCacheLock = Mutex()

    protected val cookies = ConcurrentHashMap<String, String>()

    data class HttpTextResponse(val status: Int, val body: String)

    /**
     * Abstract method that implementation should provide to fetch a fresh CSRF token.
     */
    protected abstract suspend fun fetchFreshToken(forceFresh: Boolean = false): String

    suspend fun forceFetchToken(): String = tokenLock.withLock {
        val againNow = System.currentTimeMillis()
        if (lastToken != null && (againNow - lastTokenTs) < 5000) return lastToken!!
        val freshToken = fetchFreshToken(forceFresh = true)
        if (freshToken.isNotEmpty()) {
            lastToken = freshToken
            lastTokenTs = System.currentTimeMillis()
        }
        freshToken
    }

    /**
     * Retrieves a valid CSRF token.
     * - Returns cached token if within validity window.
     * - Triggers asynchronous background refresh if approaching expiration.
     * - Blocks and forces a synchronous refresh if expired.
     */
    suspend fun getOrFetchToken(): String {
        val now = System.currentTimeMillis()
        val cached = lastToken
        val age = now - lastTokenTs

        val inWindow = cached != null && age >= tokenRefreshThresholdMs && age < tokenTimeoutMs
        if (inWindow) {
            val currentJob = refreshJob
            if (currentJob == null || !currentJob.isActive) {
                refreshJob = refreshScope.launch {
                    tokenLock.withLock {
                        lastToken = fetchFreshToken(forceFresh = false)
                        lastTokenTs = System.currentTimeMillis()
                    }
                }
            }
            return cached
        }

        if (cached != null && age < tokenTimeoutMs) return cached

        return tokenLock.withLock {
            val againNow = System.currentTimeMillis()
            if (lastToken != null && againNow - lastTokenTs < tokenTimeoutMs) return lastToken!!
            val token = fetchFreshToken(forceFresh = false)
            lastToken = token
            lastTokenTs = againNow
            token
        }
    }

    protected val activeRequests = AtomicInteger(0)
    protected val isBusy = AtomicBoolean(false)

    /**
     * Performs a form POST request, handling cookies, timeout, and concurrency limits.
     */
    protected suspend fun getPostResponse(formBody: String, uri: String = baseUrl): ResponseEntity<String> {
        if (isBusy.get()) throw ServerBusyException("Server is busy, please try again later")
        activeRequests.incrementAndGet()
        return try {
            withTimeout(requestTimeoutMs) {
                webClient.post()
                    .uri(uri)
                    .header("Accept", "text/html")
                    .header("Accept-Encoding", "gzip, deflate")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Origin", origin)
                    .header("Referer", referer)
                    .cookies { map -> cookies.forEach { (k, v) -> map.add(k, v) } }
                    .bodyValue(formBody)
                    .retrieve()
                    .toEntity(String::class.java)
                    .awaitSingle()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            isBusy.set(true)
            logger.error("Request timed out connecting to $baseUrl", e)
            throw ServerBusyException("Request timed out. Please try again later.")
        } catch (e: WebClientResponseException) {
            logger.warn("Received error status from API: {}", e.statusCode)
            ResponseEntity.status(e.statusCode).body(e.responseBodyAsString)
        } finally {
            if (activeRequests.decrementAndGet() == 0) isBusy.set(false)
        }
    }

    /**
     * Fetches the response from a redirect URL, preserving cookies.
     */
    protected suspend fun getRedirectResponseHtml(redirectUrl: String): String {
        return webClient.get()
            .uri(determineRedirect(redirectUrl))
            .header("Referer", referer)
            .header("User-Agent", "Mozilla/5.0")
            .cookies { map -> cookies.forEach { (k, v) -> map.add(k, v) } }
            .retrieve()
            .bodyToMono(String::class.java)
            .awaitSingle()
    }

    private fun determineRedirect(redirectUrl: String): String {
        if (redirectUrl.startsWith("http")) return redirectUrl
        val uriBase = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        return if (redirectUrl.startsWith("/")) "$uriBase$redirectUrl" else "$uriBase/$redirectUrl"
    }

    /**
     * Performs a POST, checking for HTML meta redirects commonly used by legacy systems.
     */
    open suspend fun postResultResponse(formBody: String, uri: String = baseUrl): HttpTextResponse {
        val postResponse = getPostResponse(formBody, uri)
        var resultHtml = postResponse.body ?: ""

        if (resultHtml.contains("meta http-equiv=\"refresh\"")) {
            val redirectUrl = """content=\s*"\s*\d+;\s*url='([^']+)'\s*""""
                .toRegex()
                .find(resultHtml)
                ?.groupValues
                ?.get(1)
            if (redirectUrl != null) resultHtml = getRedirectResponseHtml(redirectUrl)
        }
        return HttpTextResponse(
            postResponse.statusCode.value(),
            resultHtml
        )
    }
}
