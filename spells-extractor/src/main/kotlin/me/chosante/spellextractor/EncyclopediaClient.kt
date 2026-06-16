package me.chosante.spellextractor

import kotlinx.coroutines.delay
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP access to the Ankama Wakfu encyclopedia, which is the **authoritative** source for class-spell
 * mechanics (element, AP/WP cost, range, base damage) — unlike WakForge, whose dump omits the
 * elementary attack spells. See `docs/SPELLS_AND_COMBO_RESEARCH.md`.
 *
 * **Defeating the 403.** A bare request to a spell page is bounced (`302`) to
 * `account.ankama.com/sso-redirect`, which sets a session/`LANG` cookie and redirects back. A plain
 * fetcher that drops cookies therefore ends up at a 403/404. We replicate a real browser instead:
 *
 * 1. a built-in [HttpClient] with an in-memory [CookieManager] (`ACCEPT_ALL`) so the SSO cookie is
 *    captured and replayed, and [HttpClient.Redirect.NORMAL] so the bounce is followed automatically;
 * 2. a desktop-Chrome `User-Agent` + `Accept-Language` on every request;
 * 3. a one-time [prime] of the encyclopedia root to establish the session before crawling.
 *
 * **Politeness & robustness.** Requests are serialized through a small delay ([throttleMillis]) and
 * retried with exponential backoff on transient failures. Every fetched page is written to a
 * [cacheDir] keyed by URL, so a re-run is **resumable** (cached pages are not re-downloaded) and the
 * parser can be re-run offline against the cache.
 */
class EncyclopediaClient(
    private val cacheDir: File,
    private val throttleMillis: Long = 350,
    private val maxAttempts: Int = 4,
) {
    private val cookieManager = CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }

    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .cookieHandler(cookieManager)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()

    init {
        cacheDir.mkdirs()
    }

    /** Establishes the session cookie by fetching the encyclopedia root once. Call before crawling. */
    suspend fun prime() {
        fetch("$BASE/en/mmorpg/encyclopedia", cacheKey = "_prime", useCache = false)
    }

    /**
     * GETs [url], returning the page body (or `null` if every attempt failed). Served from [cacheDir]
     * when present unless [useCache] is false.
     */
    suspend fun fetch(
        url: String,
        cacheKey: String,
        useCache: Boolean = true,
    ): String? {
        val cacheFile = File(cacheDir, "$cacheKey.html")
        if (useCache && cacheFile.isFile && cacheFile.length() > MIN_VALID_BYTES) {
            return cacheFile.readText()
        }

        var attempt = 0
        var backoff = 500L
        while (attempt < maxAttempts) {
            attempt++
            try {
                delay(throttleMillis)
                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200 && response.body().length > MIN_VALID_BYTES) {
                    if (useCache) cacheFile.writeText(response.body())
                    return response.body()
                }
                System.err.println("  ! $url -> HTTP ${response.statusCode()} (attempt $attempt/$maxAttempts)")
            } catch (e: Exception) {
                System.err.println("  ! $url -> ${e.javaClass.simpleName}: ${e.message} (attempt $attempt/$maxAttempts)")
            }
            delay(backoff)
            backoff *= 2
        }
        return null
    }

    companion object {
        const val BASE = "https://www.wakfu.com"

        // A short page is the SSO/404 shell, not a real spell page; treat as a miss.
        private const val MIN_VALID_BYTES = 5_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
