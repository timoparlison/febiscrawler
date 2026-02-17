package de.febis.crawler.downloader

import de.febis.crawler.model.CrawlerResult
import java.nio.file.Path

/**
 * Manages parallel downloads with rate limiting.
 */
class DownloadQueue(
    private val downloader: FileDownloader,
    private val maxParallelDownloads: Int = 5,
    private val requestDelayMs: Long = 100
) {
    data class DownloadTask(
        val url: String,
        val targetPath: Path
    )

    suspend fun downloadAll(tasks: List<DownloadTask>): List<CrawlerResult<Path>> {
        TODO("Implement parallel downloads with semaphore and rate limiting")
    }
}
