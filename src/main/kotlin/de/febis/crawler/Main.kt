package de.febis.crawler

import de.febis.crawler.client.AuthenticatedSession
import de.febis.crawler.client.HttpClientFactory
import de.febis.crawler.config.CrawlerConfig
import de.febis.crawler.config.EnvLoader
import de.febis.crawler.downloader.DownloadQueue
import de.febis.crawler.downloader.FileDownloader
import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.model.Event
import de.febis.crawler.model.EventIndexEntry
import de.febis.crawler.output.MigrationLog
import de.febis.crawler.output.OutputWriter
import de.febis.crawler.parser.EventIndexParser
import de.febis.crawler.parser.EventPageParser
import de.febis.crawler.migrate.BoardMemberMigrator
import de.febis.crawler.migrate.MemberCompanyMigrator
import de.febis.crawler.migrate.TeamMemberMigrator
import de.febis.crawler.upload.SupabaseClient
import de.febis.crawler.upload.SupabaseUploader
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.nio.file.Files

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    logger.info { "FEBIS Crawler starting..." }

    EnvLoader.load()
    val config = parseArguments(args)

    when {
        config.migrateType != null -> runMigrate(config.crawlerConfig, config.migrateType, config.force)
        config.uploadType != null -> runUpload(config.crawlerConfig, config.uploadType, config.uploadTarget, config.force)
        config.dryRun -> runDryRun(config.crawlerConfig)
        config.singleEvent != null -> runSingleEvent(config.crawlerConfig, config.singleEvent, config.force)
        else -> runFullMigration(config.crawlerConfig)
    }
}

// ── CLI Parsing ─────────────────────────────────────────────────────────────

private data class CliConfig(
    val crawlerConfig: CrawlerConfig,
    val dryRun: Boolean = false,
    val singleEvent: String? = null,
    val force: Boolean = false,
    val uploadType: String? = null,
    val uploadTarget: String? = null,
    val migrateType: String? = null
)

private fun parseArguments(args: Array<String>): CliConfig {
    var dryRun = false
    var singleEvent: String? = null
    var force = false
    var uploadType: String? = null
    var uploadTarget: String? = null
    var migrateType: String? = null

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
            "--upload" -> {
                if (iterator.hasNext()) {
                    uploadType = iterator.next()
                    if (iterator.hasNext()) {
                        val next = iterator.next()
                        if (next.startsWith("--")) {
                            if (next == "--force") force = true
                        } else {
                            uploadTarget = next
                        }
                    }
                } else {
                    logger.error { "--upload requires a type (e.g. 'event')" }
                }
            }
            "--migrate" -> {
                if (iterator.hasNext()) {
                    migrateType = iterator.next()
                } else {
                    logger.error { "--migrate requires a type (e.g. 'teammembers')" }
                }
            }
        }
    }

    val crawlerConfig = CrawlerConfig(
        baseUrl = EnvLoader.get("FEBIS_BASE_URL") ?: "https://www.febis.org",
        loginPath = EnvLoader.get("FEBIS_LOGIN_PATH") ?: "/members-login",
        password = EnvLoader.require("FEBIS_MEMBER_PASSWORD"),
        indexPath = EnvLoader.get("FEBIS_INDEX_PATH") ?: "/general-assembly",
        supabaseProjectId = EnvLoader.get("SUPABASE_PROJECT_ID"),
        supabaseServiceRoleKey = EnvLoader.get("SUPABASE_SERVICE_ROLE_KEY")
    )

    return CliConfig(
        crawlerConfig = crawlerConfig,
        dryRun = dryRun,
        singleEvent = singleEvent,
        force = force,
        uploadType = uploadType,
        uploadTarget = uploadTarget,
        migrateType = migrateType
    )
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun requireSupabaseConfig(config: CrawlerConfig): Boolean {
    if (config.supabaseProjectId.isNullOrBlank() || config.supabaseServiceRoleKey.isNullOrBlank()) {
        logger.error { "SUPABASE_PROJECT_ID and SUPABASE_SERVICE_ROLE_KEY must be set in .env" }
        return false
    }
    return true
}

private fun <T> withHttpClient(block: suspend (HttpClient) -> T): T = runBlocking {
    val client = HttpClientFactory.create()
    try {
        block(client)
    } finally {
        client.close()
    }
}

private suspend fun authenticate(client: HttpClient, config: CrawlerConfig, targetUrl: String): AuthenticatedSession? {
    val session = AuthenticatedSession(client, config)
    return when (val result = session.authenticate(targetUrl)) {
        is CrawlerResult.Failure -> {
            logger.error { "Authentication failed: ${result.error}" }
            null
        }
        is CrawlerResult.Success -> session
    }
}

private fun logResult(label: String, result: CrawlerResult<Unit>) {
    when (result) {
        is CrawlerResult.Success -> logger.info { "$label completed successfully" }
        is CrawlerResult.Failure -> logger.error { "$label failed: ${result.error}" }
    }
}

// ── Crawl ───────────────────────────────────────────────────────────────────

private fun runDryRun(config: CrawlerConfig) {
    logger.info { "Running in dry-run mode - listing events" }

    withHttpClient { client ->
        val indexUrl = "${config.baseUrl}${config.loginPath}${config.indexPath}/"
        val session = authenticate(client, config, indexUrl) ?: return@withHttpClient

        val events = fetchEventIndex(session, config) ?: return@withHttpClient

        val migrationLog = MigrationLog(config.outputDir)
        val crawledIds = migrationLog.getCrawledEventIds()

        logger.info { "=== FEBIS Events (${events.size} total, ${crawledIds.size} already crawled) ===" }
        for (entry in events) {
            val status = if (entry.id in crawledIds) "[DONE]" else "[    ]"
            logger.info { "  $status ${entry.id} - ${entry.title}" }
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

    withHttpClient { client ->
        val eventUrl = "${config.baseUrl}${config.loginPath}${config.indexPath}/$eventId/"
        val session = authenticate(client, config, eventUrl) ?: return@withHttpClient
        processEvent(client, session, config, eventId)
    }
}

private fun runFullMigration(config: CrawlerConfig) {
    logger.info { "Starting full migration" }

    withHttpClient { client ->
        val indexUrl = "${config.baseUrl}${config.loginPath}${config.indexPath}/"
        val session = authenticate(client, config, indexUrl) ?: return@withHttpClient

        val events = fetchEventIndex(session, config) ?: return@withHttpClient

        val outputWriter = OutputWriter(config.outputDir)
        outputWriter.writeEventsIndex(events)

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
    }
}

private suspend fun fetchEventIndex(session: AuthenticatedSession, config: CrawlerConfig): List<EventIndexEntry>? {
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

private suspend fun processEvent(client: HttpClient, session: AuthenticatedSession, config: CrawlerConfig, eventId: String) {
    val eventUrl = "${config.baseUrl}${config.loginPath}${config.indexPath}/$eventId/"

    val html = when (val pageResult = session.fetchPage(eventUrl)) {
        is CrawlerResult.Success -> pageResult.data
        is CrawlerResult.Failure -> {
            logger.error { "Failed to fetch event page: ${pageResult.error}" }
            return
        }
    }

    val eventType = config.indexPath.trimStart('/')
    val parser = EventPageParser(config.baseUrl, eventType)
    val event = when (val parseResult = parser.parse(html, eventId, eventUrl)) {
        is CrawlerResult.Success -> parseResult.data
        is CrawlerResult.Failure -> {
            logger.error { "Failed to parse event page: ${parseResult.error}" }
            return
        }
    }

    val outputWriter = OutputWriter(config.outputDir)
    val dirs = outputWriter.createEventDirectories(eventId)

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

    outputWriter.writeEvent(event)
    logger.info { "Event '$eventId' crawled successfully → ${dirs.root}" }
}

// ── Migrate ─────────────────────────────────────────────────────────────────

private fun runMigrate(config: CrawlerConfig, type: String, force: Boolean) {
    if (!requireSupabaseConfig(config)) return

    when (type) {
        "teammembers" -> migrateTeamMembers(config, force)
        "boardmembers" -> migrateBoardMembers(config, force)
        "companies" -> migrateCompanies(config, force)
        else -> logger.error { "Unknown migrate type '$type'. Supported: teammembers, boardmembers, companies" }
    }
}

private fun migrateTeamMembers(config: CrawlerConfig, force: Boolean) {
    withHttpClient { client ->
        val adminUrl = "${config.baseUrl}${config.loginPath}/administration/"
        val session = authenticate(client, config, adminUrl) ?: return@withHttpClient

        val supabase = SupabaseClient(client, config)
        val result = TeamMemberMigrator(session, client, supabase, config).migrate(force)
        logResult("Team members migration", result)
    }
}

private fun migrateBoardMembers(config: CrawlerConfig, force: Boolean) {
    withHttpClient { client ->
        val supabase = SupabaseClient(client, config)
        val result = BoardMemberMigrator(client, supabase, config).migrate(force)
        logResult("Board members migration", result)
    }
}

private fun migrateCompanies(config: CrawlerConfig, force: Boolean) {
    withHttpClient { client ->
        val supabase = SupabaseClient(client, config)
        val result = MemberCompanyMigrator(client, supabase, config).migrate(force)
        logResult("Member companies migration", result)
    }
}

// ── Upload ──────────────────────────────────────────────────────────────────

private fun runUpload(config: CrawlerConfig, type: String, target: String?, force: Boolean) {
    when (type) {
        "event" -> {
            if (target == null) {
                logger.error { "--upload event requires an event ID, e.g. --upload event 2025-rhodes" }
                return
            }
            uploadEvent(config, target, force)
        }
        else -> logger.error { "Unknown upload type '$type'. Supported: event" }
    }
}

private fun uploadEvent(config: CrawlerConfig, eventId: String, force: Boolean) {
    if (!requireSupabaseConfig(config)) return

    val eventDir = config.outputDir.resolve("events").resolve(eventId)
    val eventJsonFile = eventDir.resolve("event.json")
    if (!Files.exists(eventJsonFile)) {
        logger.error { "Event not crawled yet: $eventJsonFile not found. Crawl it first with --event $eventId" }
        return
    }

    val json = Json { ignoreUnknownKeys = true }
    val event = json.decodeFromString<Event>(Files.readString(eventJsonFile))
    logger.info { "Loaded event '${event.id}' (${event.title})" }

    withHttpClient { client ->
        val supabase = SupabaseClient(client, config)
        val result = SupabaseUploader(supabase, eventDir).upload(event, force)
        logResult("Event '$eventId' upload", result)
    }
}
