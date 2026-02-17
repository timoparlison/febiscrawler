package de.febis.crawler

import de.febis.crawler.client.AuthenticatedSession
import de.febis.crawler.client.HttpClientFactory
import de.febis.crawler.config.CrawlerConfig
import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.output.OutputWriter
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
        when (val arg = iterator.next()) {
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

            when (val authResult = session.authenticate(eventUrl)) {
                is CrawlerResult.Success -> {
                    logger.info { "Fetching event page: $eventUrl" }

                    when (val pageResult = session.fetchPage(eventUrl)) {
                        is CrawlerResult.Success -> {
                            val html = pageResult.data
                            logger.info { "Event page loaded successfully (${html.length} chars)" }

                            // Create output directories
                            val outputWriter = OutputWriter(config.outputDir)
                            val dirs = outputWriter.createEventDirectories(eventId)
                            logger.info { "Created output structure at ${dirs.root}" }

                            // TODO: Parse event page HTML and populate event.json
                            // TODO: Download documents and images
                        }
                        is CrawlerResult.Failure -> {
                            logger.error { "Failed to fetch event page: ${pageResult.error}" }
                        }
                    }
                }
                is CrawlerResult.Failure -> {
                    logger.error { "Authentication failed: ${authResult.error}" }
                }
            }
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
