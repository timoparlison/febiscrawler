package de.febis.crawler

import de.febis.crawler.client.AuthenticatedSession
import de.febis.crawler.client.HttpClientFactory
import de.febis.crawler.config.CrawlerConfig
import de.febis.crawler.downloader.DownloadQueue
import de.febis.crawler.downloader.FileDownloader
import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.output.OutputWriter
import de.febis.crawler.parser.EventPageParser
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "FEBIS Crawler starting..." }

    val config = parseArguments(args)

    when {
        config.dryRun -> runDryRun(config.crawlerConfig)
        config.singleEvent != null -> runSingleEvent(config.crawlerConfig, config.singleEvent)
        config.resume -> runResume(config.crawlerConfig)
        else -> runFullMigration(config.crawlerConfig)
    }
}

private data class CliConfig(
    val crawlerConfig: CrawlerConfig,
    val dryRun: Boolean = false,
    val singleEvent: String? = null,
    val resume: Boolean = false
)

private fun parseArguments(args: Array<String>): CliConfig {
    var dryRun = false
    var singleEvent: String? = null
    var resume = false

    val iterator = args.iterator()
    while (iterator.hasNext()) {
        when (iterator.next()) {
            "--dry-run" -> dryRun = true
            "--resume" -> resume = true
            "--event" -> {
                if (iterator.hasNext()) {
                    singleEvent = iterator.next()
                } else {
                    logger.error { "--event requires an event ID" }
                }
            }
        }
    }

    val crawlerConfig = CrawlerConfig(
        baseUrl = System.getenv("FEBIS_BASE_URL") ?: "https://www.febis.org",
        indexPath = System.getenv("FEBIS_INDEX_PATH") ?: "/general-assembly"
    )

    return CliConfig(
        crawlerConfig = crawlerConfig,
        dryRun = dryRun,
        singleEvent = singleEvent,
        resume = resume
    )
}

private fun runDryRun(config: CrawlerConfig) {
    logger.info { "Running in dry-run mode - only loading index" }
    TODO("Implement dry-run: load and display event index")
}

private fun runSingleEvent(config: CrawlerConfig, eventId: String) {
    logger.info { "Processing single event: $eventId" }

    runBlocking {
        val client = HttpClientFactory.create()
        try {
            val session = AuthenticatedSession(client, config)
            val eventUrl = "${config.baseUrl}${config.loginPath}${config.indexPath}/$eventId/"

            // 1. Authenticate
            when (val authResult = session.authenticate(eventUrl)) {
                is CrawlerResult.Failure -> {
                    logger.error { "Authentication failed: ${authResult.error}" }
                    return@runBlocking
                }
                is CrawlerResult.Success -> {}
            }

            // 2. Fetch event page
            val html = when (val pageResult = session.fetchPage(eventUrl)) {
                is CrawlerResult.Success -> pageResult.data
                is CrawlerResult.Failure -> {
                    logger.error { "Failed to fetch event page: ${pageResult.error}" }
                    return@runBlocking
                }
            }

            // 3. Parse HTML
            val parser = EventPageParser(config.baseUrl)
            val event = when (val parseResult = parser.parse(html, eventId, eventUrl)) {
                is CrawlerResult.Success -> parseResult.data
                is CrawlerResult.Failure -> {
                    logger.error { "Failed to parse event page: ${parseResult.error}" }
                    return@runBlocking
                }
            }

            // 4. Create output directories
            val outputWriter = OutputWriter(config.outputDir)
            val dirs = outputWriter.createEventDirectories(eventId)

            // 5. Download all files
            val downloader = FileDownloader(client, config.maxRetries)
            val queue = DownloadQueue(downloader, config.maxParallelDownloads, config.requestDelayMs)

            val downloadTasks = mutableListOf<DownloadQueue.DownloadTask>()

            // Documents
            for (doc in event.documents) {
                downloadTasks.add(DownloadQueue.DownloadTask(doc.originalUrl, dirs.root.resolve(doc.localPath)))
            }

            // Hotel images
            for (img in event.hotelImages) {
                downloadTasks.add(DownloadQueue.DownloadTask(img.originalUrl, dirs.root.resolve(img.localPath)))
            }

            // Gallery images
            for (gallery in event.galleries) {
                val gallerySlug = gallery.images.firstOrNull()?.localPath
                    ?.substringAfter("images/")?.substringBefore("/")
                if (gallerySlug != null) {
                    outputWriter.createGalleryDirectory(eventId, gallerySlug)
                }
                for (img in gallery.images) {
                    downloadTasks.add(DownloadQueue.DownloadTask(img.originalUrl, dirs.root.resolve(img.localPath)))
                }
            }

            val results = queue.downloadAll(downloadTasks)
            val succeeded = results.count { it is CrawlerResult.Success }
            val failed = results.count { it is CrawlerResult.Failure }
            logger.info { "Downloads complete: $succeeded succeeded, $failed failed" }

            // 6. Write event.json
            outputWriter.writeEvent(event)
            logger.info { "Event '$eventId' crawled successfully → ${dirs.root}" }

        } finally {
            client.close()
        }
    }
}

private fun runResume(config: CrawlerConfig) {
    logger.info { "Resuming previous migration" }
    TODO("Implement resume from migration state")
}

private fun runFullMigration(config: CrawlerConfig) {
    logger.info { "Starting full migration" }
    TODO("Implement full migration")
}
