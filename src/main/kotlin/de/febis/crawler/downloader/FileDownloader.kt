package de.febis.crawler.downloader

import de.febis.crawler.model.CrawlerResult
import io.ktor.client.*
import java.nio.file.Path

/**
 * Downloads files with retry logic.
 */
class FileDownloader(
    private val client: HttpClient,
    private val maxRetries: Int = 3
) {
    suspend fun download(url: String, targetPath: Path): CrawlerResult<Path> {
        TODO("Implement file download with retry logic")
    }
}
