package com.fablab503.velotrack.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** An HTTP range request failed; [retryable] says whether another attempt makes sense. */
class RangeException(message: String, val retryable: Boolean) : IOException(message)

/**
 * HTTP byte-range reader over OkHttp. Every fetch requires a `206 Partial Content` answer whose
 * `Content-Range` matches the request exactly and reads exactly the requested number of bytes;
 * anything else (notably a `200` full-body answer from a server ignoring `Range`) is refused
 * without reading the body. Transient failures are retried with exponential backoff.
 *
 * Cancelling the calling coroutine cancels the in-flight OkHttp call.
 */
class RangeClient(private val client: OkHttpClient) {

    /**
     * Returns bytes `[first, last]` (inclusive) of [url].
     *
     * @throws RangeException for non-retryable protocol errors (after retries for retryable ones)
     * @throws IOException for network errors after [MAX_ATTEMPTS] attempts
     */
    suspend fun fetch(url: String, first: Long, last: Long): ByteArray {
        require(first >= 0L && last >= first) { "Invalid byte range $first-$last" }
        require(last - first + 1 <= MAX_RANGE_BYTES) { "Range $first-$last exceeds $MAX_RANGE_BYTES bytes" }
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return fetchOnce(url, first, last)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // An OkHttp "Canceled" IOException after coroutine cancellation must not be retried.
                currentCoroutineContext().ensureActive()
                val retryable = (e as? RangeException)?.retryable ?: true
                attempt++
                if (!retryable || attempt >= MAX_ATTEMPTS) throw e
                delay(backoffMs(attempt))
            }
        }
    }

    private suspend fun fetchOnce(url: String, first: Long, last: Long): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$first-$last")
            .header("Accept-Encoding", "identity")
            .build()
        val call: Call = client.newCall(request)
        return withContext(Dispatchers.IO) {
            // Blocking socket reads are not interruptible; cancel the OkHttp call when the coroutine
            // is cancelled so execute()/read() fail promptly.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { response -> readRange(response, first, last) }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun readRange(response: Response, first: Long, last: Long): ByteArray {
        when (response.code) {
            206 -> Unit
            200 -> throw RangeException("Server ignored the Range header (HTTP 200)", retryable = false)
            408, 425, 429, 500, 502, 503, 504 -> throw RangeException("HTTP ${response.code}", retryable = true)
            else -> throw RangeException("HTTP ${response.code}", retryable = false)
        }
        val contentRange = response.header("Content-Range")
            ?: throw RangeException("206 without Content-Range", retryable = false)
        val match = CONTENT_RANGE.matchEntire(contentRange.trim())
            ?: throw RangeException("Bad Content-Range '$contentRange'", retryable = false)
        val gotFirst = match.groupValues[1].toLongOrNull()
        val gotLast = match.groupValues[2].toLongOrNull()
        if (gotFirst != first || gotLast != last) {
            throw RangeException("Content-Range $contentRange does not match requested $first-$last", retryable = false)
        }
        val expected = (last - first + 1).toInt()
        val body = response.body ?: throw RangeException("206 without body", retryable = true)
        val out = ByteArray(expected)
        var n = 0
        body.byteStream().use { input ->
            while (n < expected) {
                val r = input.read(out, n, expected - n)
                if (r < 0) break
                n += r
            }
            if (n == expected && input.read() != -1) {
                throw RangeException("Body longer than Content-Range", retryable = false)
            }
        }
        if (n != expected) throw RangeException("Short body: $n of $expected bytes", retryable = true)
        return out
    }

    private fun backoffMs(attempt: Int): Long {
        val base = minOf(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS shl (attempt - 1))
        return base + Random.nextLong(JITTER_MS)
    }

    companion object {
        const val TIMEOUT_SECONDS = 30L
        const val MAX_ATTEMPTS = 5

        /** Upper bound for a single request; the planner caps ranges at 8 MiB, leaves are ~1 MiB. */
        const val MAX_RANGE_BYTES = 64L shl 20

        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 16_000L
        private const val JITTER_MS = 500L
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

        /** OkHttp client with 30 s connect/read/write timeouts, suitable for range downloads. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
