package de.febis.crawler.parser

import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.model.EventIndexEntry

/**
 * Parses the event overview page to extract all event links.
 */
class EventIndexParser {
    fun parse(html: String): CrawlerResult<List<EventIndexEntry>> {
        TODO("Implement parsing of event index page")
    }
}
