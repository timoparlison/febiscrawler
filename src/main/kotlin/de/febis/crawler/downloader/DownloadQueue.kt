package de.febis.crawler.downloader

import de.febis.crawler.model.CrawlerResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

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
        if (tasks.isEmpty()) return emptyList()

        logger.info { "Downloading ${tasks.size} files (max $maxParallelDownloads parallel)" }
        val semaphore = Semaphore(maxParallelDownloads)

        return coroutineScope {
            tasks.mapIndexed { idx, task ->
                async {
                    semaphore.withPermit {
                        delay(requestDelayMs)
                        logger.info { "[${idx + 1}/${tasks.size}] Downloading ${task.url.substringAfterLast("/").substringBefore("?")}" }
                        downloader.download(task.url, task.targetPath)
                    }
                }
            }.awaitAll()
        }
    }
}
