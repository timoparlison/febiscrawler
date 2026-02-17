package de.febis.crawler.parser

import de.febis.crawler.model.CrawlerError
import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.model.EventIndexEntry
import mu.KotlinLogging
import org.jsoup.Jsoup

private val logger = KotlinLogging.logger {}

/**
 * Parses the event overview page to extract all event links.
 *
 * Looks for links matching the pattern: {basePath}/{event-id}/
 * e.g. /members-login/general-assembly/2025-rhodes/
 */
class EventIndexParser(private val basePath: String) {

    fun parse(html: String): CrawlerResult<List<EventIndexEntry>> {
        return try {
            val doc = Jsoup.parse(html)
            val entries = mutableListOf<EventIndexEntry>()

            // Match links like /members-login/general-assembly/2025-rhodes/
            val pattern = Regex("${Regex.escape(basePath)}/([^/]+)/?$")

            for (link in doc.select("a[href]")) {
                val href = link.attr("href")
                val match = pattern.find(href) ?: continue
                val eventId = match.groupValues[1]

                if (eventId.isBlank()) continue
                if (entries.any { it.id == eventId }) continue

                val title = link.text().trim().takeIf { it.isNotBlank() } ?: eventId

                entries.add(EventIndexEntry(
                    id = eventId,
                    title = title,
                    url = href
                ))
            }

            logger.info { "Found ${entries.size} events on index page" }
            if (entries.isEmpty()) {
                CrawlerResult.Failure(CrawlerError.ParseError(basePath, "No event links found on index page"))
            } else {
                CrawlerResult.Success(entries)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse event index" }
            CrawlerResult.Failure(CrawlerError.ParseError(basePath, "Parse error: ${e.message}"))
        }
    }
}
