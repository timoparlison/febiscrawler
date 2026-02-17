package de.febis.crawler.downloader

import de.febis.crawler.model.CrawlerError
import de.febis.crawler.model.CrawlerResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

class FileDownloader(
    private val client: HttpClient,
    private val maxRetries: Int = 3
) {
    suspend fun download(url: String, targetPath: Path): CrawlerResult<Path> {
        Files.createDirectories(targetPath.parent)

        for (attempt in 1..maxRetries) {
            try {
                val response = client.get(url)
                if (response.status != HttpStatusCode.OK) {
                    logger.warn { "HTTP ${response.status} for $url (attempt $attempt/$maxRetries)" }
                    if (attempt < maxRetries) { delay(1000L * attempt); continue }
                    return CrawlerResult.Failure(CrawlerError.NetworkError(url, Exception("HTTP ${response.status}")))
                }

                val bytes = response.readBytes()
                Files.write(targetPath, bytes)
                logger.debug { "Downloaded ${bytes.size} bytes → $targetPath" }
                return CrawlerResult.Success(targetPath)
            } catch (e: Exception) {
                logger.warn { "Download failed for $url (attempt $attempt/$maxRetries): ${e.message}" }
                if (attempt < maxRetries) { delay(1000L * attempt) } else {
                    return CrawlerResult.Failure(CrawlerError.NetworkError(url, e))
                }
            }
        }
        return CrawlerResult.Failure(CrawlerError.NetworkError(url, Exception("Max retries exceeded")))
    }
}
