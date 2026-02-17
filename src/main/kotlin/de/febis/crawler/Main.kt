package de.febis.crawler

import de.febis.crawler.client.AuthenticatedSession
import de.febis.crawler.client.HttpClientFactory
import de.febis.crawler.config.CrawlerConfig
import de.febis.crawler.downloader.DownloadQueue
import de.febis.crawler.downloader.FileDownloader
import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.model.EventIndexEntry
import de.febis.crawler.output.MigrationLog
import de.febis.crawler.output.OutputWriter
import de.febis.crawler.parser.EventIndexParser
import de.febis.crawler.parser.EventPageParser
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "FEBIS Crawler starting..." }

    val config = parseArguments(args)

    when {
        config.dryRun -> runDryRun(config.crawlerConfig)
        config.singleEvent != null -> runSingleEvent(config.crawlerConfig, config.singleEvent, config.force)
        else -> runFullMigration(config.crawlerConfig)
    }
}

private data class CliConfig(
    val crawlerConfig: CrawlerConfig,
    val dryRun: Boolean = false,
    val singleEvent: String? = null,
    val force: Boolean = false
)

private fun parseArguments(args: Array<String>): CliConfig {
    var dryRun = false
    var singleEvent: String? = null
    var force = false

    val iterator = args.iterator()
    while (iterator.hasNext()) {
        when (iterator.next()) {
            "--dry-run" -> dryRun = true
            "--force" -> force = true
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
        force = force
    )
}

private fun runDryRun(config: CrawlerConfig) {
    logger.info { "Running in dry-run mode - listing events" }

    runBlocking {
        val client = HttpClientFactory.create()
        try {
            val session = AuthenticatedSession(client, config)
            val indexUrl = "${config.baseUrl}${config.loginPath}${config.indexPath}/"

            when (val authResult = session.authenticate(indexUrl)) {
                is CrawlerResult.Failure -> {
                    logger.error { "Authentication failed: ${authResult.error}" }
                    return@runBlocking
                }
                is CrawlerResult.Success -> {}
            }

            val events = fetchEventIndex(session, config) ?: return@runBlocking

            val migrationLog = MigrationLog(config.outputDir)
            val crawledIds = migrationLog.getCrawledEventIds()

            logger.info { "=== FEBIS Events (${events.size} total, ${crawledIds.size} already crawled) ===" }
            for (entry in events) {
                val status = if (entry.id in crawledIds) "[DONE]" else "[    ]"
                logger.info { "  $status ${entry.id} - ${entry.title}" }
            }
        } finally {
            client.close()
        }
    }
}

private fun runSingleEvent(config: CrawlerConfig, eventId: String, force: Boolean) {
    logger.info { "Processing single event: $eventId" }

    val migrationLog = MigrationLog(config.outputDir)
    if (!force && migrationLog.isEventCrawled(eventId)) {
        logger.info { "Event '$eventId' already crawled. Use --force to re-download." }
        return
    }

    runBlocking {
        val client = HttpClientFactory.create()
        try {
            val session = AuthenticatedSession(client, config)
            val eventUrl = "${config.baseUrl}${config.loginPath}${config.indexPath}/$eventId/"

            when (val authResult = session.authenticate(eventUrl)) {
                is CrawlerResult.Failure -> {
                    logger.error { "Authentication failed: ${authResult.error}" }
                    return@runBlocking
                }
                is CrawlerResult.Success -> {}
            }

            processEvent(client, session, config, eventId)
        } finally {
            client.close()
        }
    }
}

private fun runFullMigration(config: CrawlerConfig) {
    logger.info { "Starting full migration" }

    runBlocking {
        val client = HttpClientFactory.create()
        try {
            val session = AuthenticatedSession(client, config)
            val indexUrl = "${config.baseUrl}${config.loginPath}${config.indexPath}/"

            when (val authResult = session.authenticate(indexUrl)) {
                is CrawlerResult.Failure -> {
                    logger.error { "Authentication failed: ${authResult.error}" }
                    return@runBlocking
                }
                is CrawlerResult.Success -> {}
            }

            // Fetch and parse event index
            val events = fetchEventIndex(session, config) ?: return@runBlocking

            // Save events index
            val outputWriter = OutputWriter(config.outputDir)
            outputWriter.writeEventsIndex(events)

            // Determine which events still need processing
            val migrationLog = MigrationLog(config.outputDir)
            val crawledIds = migrationLog.getCrawledEventIds()
            val toProcess = events.filter { it.id !in crawledIds }

            logger.info { "Found ${events.size} events total, ${crawledIds.size} already crawled, ${toProcess.size} to process" }

            var succeeded = 0
            var failed = 0

            for ((idx, entry) in toProcess.withIndex()) {
                logger.info { "=== [${idx + 1}/${toProcess.size}] Processing event: ${entry.id} ===" }
                try {
                    processEvent(client, session, config, entry.id)
                    succeeded++
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process event ${entry.id}" }
                    failed++
                }
            }

            logger.info { "=== Migration complete: $succeeded succeeded, $failed failed, ${crawledIds.size} skipped ===" }
        } finally {
            client.close()
        }
    }
}

/**
 * Fetches and parses the event index page to discover all events.
 */
private suspend fun fetchEventIndex(
    session: AuthenticatedSession,
    config: CrawlerConfig
): List<EventIndexEntry>? {
    val indexUrl = "${config.baseUrl}${config.loginPath}${config.indexPath}/"

    val html = when (val result = session.fetchPage(indexUrl)) {
        is CrawlerResult.Success -> result.data
        is CrawlerResult.Failure -> {
            logger.error { "Failed to fetch event index: ${result.error}" }
            return null
        }
    }

    val basePath = "${config.loginPath}${config.indexPath}"
    val parser = EventIndexParser(basePath)
    return when (val result = parser.parse(html)) {
        is CrawlerResult.Success -> result.data
        is CrawlerResult.Failure -> {
            logger.error { "Failed to parse event index: ${result.error}" }
            null
        }
    }
}

/**
 * Processes a single event: fetch page, parse, download files, write event.json.
 */
private suspend fun processEvent(
    client: HttpClient,
    session: AuthenticatedSession,
    config: CrawlerConfig,
    eventId: String
) {
    val eventUrl = "${config.baseUrl}${config.loginPath}${config.indexPath}/$eventId/"

    // 1. Fetch event page
    val html = when (val pageResult = session.fetchPage(eventUrl)) {
        is CrawlerResult.Success -> pageResult.data
        is CrawlerResult.Failure -> {
            logger.error { "Failed to fetch event page: ${pageResult.error}" }
            return
        }
    }

    // 2. Parse HTML
    val parser = EventPageParser(config.baseUrl)
    val event = when (val parseResult = parser.parse(html, eventId, eventUrl)) {
        is CrawlerResult.Success -> parseResult.data
        is CrawlerResult.Failure -> {
            logger.error { "Failed to parse event page: ${parseResult.error}" }
            return
        }
    }

    // 3. Create output directories
    val outputWriter = OutputWriter(config.outputDir)
    val dirs = outputWriter.createEventDirectories(eventId)

    // 4. Download all files
    val downloader = FileDownloader(client, config.maxRetries)
    val queue = DownloadQueue(downloader, config.maxParallelDownloads, config.requestDelayMs)

    val downloadTasks = mutableListOf<DownloadQueue.DownloadTask>()

    for (doc in event.documents) {
        downloadTasks.add(DownloadQueue.DownloadTask(doc.originalUrl, dirs.root.resolve(doc.localPath)))
    }
    for (img in event.hotelImages) {
        downloadTasks.add(DownloadQueue.DownloadTask(img.originalUrl, dirs.root.resolve(img.localPath)))
    }
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
    val downloadSucceeded = results.count { it is CrawlerResult.Success }
    val downloadFailed = results.count { it is CrawlerResult.Failure }
    logger.info { "Downloads complete: $downloadSucceeded succeeded, $downloadFailed failed" }

    // 5. Write event.json (marks event as crawled for skip-check)
    outputWriter.writeEvent(event)
    logger.info { "Event '$eventId' crawled successfully → ${dirs.root}" }
}
