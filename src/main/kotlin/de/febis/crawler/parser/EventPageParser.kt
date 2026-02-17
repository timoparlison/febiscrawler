package de.febis.crawler.parser

import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.model.Event

/**
 * Parses individual event pages to extract all event data.
 */
class EventPageParser {
    fun parse(html: String, eventId: String): CrawlerResult<Event> {
        TODO("Implement parsing of individual event pages")
    }
}
