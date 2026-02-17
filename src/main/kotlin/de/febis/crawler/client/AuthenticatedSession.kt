package de.febis.crawler.client

import de.febis.crawler.config.CrawlerConfig
import de.febis.crawler.model.CrawlerError
import de.febis.crawler.model.CrawlerResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import mu.KotlinLogging
import java.net.URLEncoder

private val logger = KotlinLogging.logger {}

/**
 * Handles authentication against Jimdo password-protected pages.
 *
 * Login flow:
 * 1. POST password to /protected/?comeFrom=<target-url>
 * 2. Server sets auth cookie and redirects to target
 * 3. Subsequent requests use the cookie automatically
 */
class AuthenticatedSession(
    private val client: HttpClient,
    private val config: CrawlerConfig
) {
    /**
     * Authenticates by POSTing password + do_login=yes directly to a protected page.
     * Jimdo sets a session cookie on success, which is reused for all subsequent requests.
     *
     * Flow:
     * 1. GET protected page → may redirect to /protected/ login form
     * 2. POST password + do_login to the target page URL (form action)
     * 3. Cookie is set, subsequent GETs return actual content
     */
    suspend fun authenticate(targetPageUrl: String? = null): CrawlerResult<Unit> {
        return try {
            val targetUrl = targetPageUrl ?: "${config.baseUrl}${config.loginPath}/members-area/"

            // Step 1: GET to establish session cookies
            logger.info { "Step 1: GET $targetUrl to establish session" }
            val getResponse = client.get(targetUrl)
            val getBody = getResponse.bodyAsText()
            logger.debug { "GET response: ${getResponse.status}, ${getBody.length} chars" }

            // If no login form (actual form elements, not just CSS references), already authenticated
            if (!(getBody.contains("do_login") && getBody.contains("id=\"password\""))) {
                logger.info { "Already authenticated" }
                return CrawlerResult.Success(Unit)
            }

            // Step 2: POST password to the target page URL
            logger.info { "Step 2: POST password to $targetUrl" }
            val response = client.submitForm(
                url = targetUrl,
                formParameters = parameters {
                    append("password", config.password)
                    append("do_login", "yes")
                }
            )

            val body = response.bodyAsText()
            logger.debug { "POST response: ${response.status}, ${body.length} chars" }

            // If we still see the actual login form (not just CSS), auth failed
            if (body.contains("<form") && body.contains("do_login") && body.contains("id=\"password\"")) {
                logger.error { "Authentication failed - still seeing login form" }
                CrawlerResult.Failure(CrawlerError.AuthError("Login failed - wrong password?"))
            } else {
                logger.info { "Authentication successful" }
                CrawlerResult.Success(Unit)
            }
        } catch (e: Exception) {
            logger.error(e) { "Authentication error" }
            CrawlerResult.Failure(CrawlerError.AuthError("Authentication error: ${e.message}"))
        }
    }

    suspend fun fetchPage(url: String): CrawlerResult<String> {
        return try {
            logger.debug { "Fetching: $url" }
            val response = client.get(url)

            if (response.status == HttpStatusCode.OK) {
                val body = response.bodyAsText()
                logger.debug { "Fetched ${body.length} chars from $url" }
                CrawlerResult.Success(body)
            } else {
                logger.warn { "HTTP ${response.status} for $url" }
                CrawlerResult.Failure(CrawlerError.NetworkError(url, Exception("HTTP ${response.status}")))
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch $url" }
            CrawlerResult.Failure(CrawlerError.NetworkError(url, e))
        }
    }
}
