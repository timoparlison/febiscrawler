package de.febis.crawler.client

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cookies.*
import io.ktor.http.*

/**
 * Factory for creating configured HTTP clients.
 */
object HttpClientFactory {
    fun create(): HttpClient = HttpClient(CIO) {
        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }

        engine {
            requestTimeout = 30_000
            endpoint {
                connectTimeout = 10_000
            }
        }

        defaultRequest {
            headers.append(HttpHeaders.UserAgent, "FEBIS-Migration-Crawler/1.0")
        }
    }
}
