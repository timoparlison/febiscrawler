package de.febis.crawler.parser

import de.febis.crawler.model.*
import de.febis.crawler.model.Document as CrawlerDocument
import de.febis.crawler.util.Slugify
import mu.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document as JsoupDocument
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}
private val datePattern = Regex("""(\d{2})\.(\d{2})\.(\d{4})""")
private val youtubeIdPattern = Regex("""/embed/([a-zA-Z0-9_-]+)""")

class EventPageParser(private val baseUrl: String) {

    fun parse(html: String, eventId: String, sourceUrl: String): CrawlerResult<Event> {
        return try {
            val doc = Jsoup.parse(html)
            val content = doc.selectFirst("#content_area")
                ?: return CrawlerResult.Failure(CrawlerError.ParseError(sourceUrl, "No #content_area found"))

            val title = parseTitle(doc, content)
            val (dateStart, dateEnd) = parseDates(content)
            val (city, country) = parseLocation(doc)
            val (hotelName, hotelAddress, hotelWebsite) = parseHotelInfo(content)
            val hotelImages = parseHotelImages(content, eventId)
            val documents = parseDocuments(content, eventId)
            val videos = parseVideos(content)
            val galleries = parseGalleries(content, eventId)

            val event = Event(
                id = eventId,
                title = title,
                eventType = "general-assembly",
                dateStart = dateStart,
                dateEnd = dateEnd,
                locationCity = city,
                locationCountry = country,
                sourceUrl = sourceUrl,
                crawledAt = Instant.now().toString(),
                hotelName = hotelName,
                hotelAddress = hotelAddress,
                hotelWebsite = hotelWebsite,
                hotelImages = hotelImages,
                documents = documents,
                videos = videos,
                galleries = galleries
            )

            logger.info { "Parsed event '${event.title}': ${documents.size} docs, ${videos.size} videos, ${galleries.size} galleries (${galleries.sumOf { it.images.size }} images), ${hotelImages.size} hotel images" }
            CrawlerResult.Success(event)
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse event page" }
            CrawlerResult.Failure(CrawlerError.ParseError(sourceUrl, "Parse error: ${e.message}"))
        }
    }

    private fun parseTitle(doc: JsoupDocument, content: Element): String {
        // Prefer og:title, fallback to first h1
        return doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: content.selectFirst(".j-header h1")?.text()
            ?: "Unknown"
    }

    private fun parseDates(content: Element): Pair<String?, String?> {
        // Dates are in the first h2, format: "24.09.2025 - 26.09.2025"
        val dateText = content.selectFirst(".j-header h2")?.text() ?: return null to null
        val matches = datePattern.findAll(dateText).toList()
        if (matches.isEmpty()) return null to null

        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val start = try {
            LocalDate.parse(matches[0].value, fmt).toString()
        } catch (e: Exception) { null }
        val end = if (matches.size > 1) {
            try { LocalDate.parse(matches[1].value, fmt).toString() } catch (e: Exception) { null }
        } else null

        return start to end
    }

    private fun parseLocation(doc: JsoupDocument): Pair<String?, String?> {
        // og:description often contains "HOTEL NAME ADDRESS, CITY, COUNTRY, ZIP"
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: return null to null
        // Try to extract city from the description
        val parts = desc.split(",").map { it.trim() }
        // Typically: "HOTEL NAME ADDRESS, CITY, COUNTRY, ZIP ..."
        return if (parts.size >= 3) {
            val city = parts[parts.size - 3].takeIf { it.isNotBlank() }
            val country = parts[parts.size - 2].takeIf { it.isNotBlank() }
            city to country
        } else null to null
    }

    data class HotelInfoParsed(val name: String?, val address: String?, val website: String?)

    private fun parseHotelInfo(content: Element): HotelInfoParsed {
        // Find the "Hotel information" section heading
        val hotelHeader = content.select(".j-header h2").firstOrNull {
            it.text().contains("Hotel", ignoreCase = true)
        } ?: return HotelInfoParsed(null, null, null)

        // The text module following the hotel header contains details
        val hotelModule = hotelHeader.closest(".j-module")
        var sibling = hotelModule?.nextElementSibling()

        // Walk siblings to find the j-text module with hotel details
        while (sibling != null && !sibling.hasClass("j-text")) {
            if (sibling.selectFirst("h2") != null) break // next section
            sibling = sibling.nextElementSibling()
        }

        if (sibling == null || !sibling.hasClass("j-text")) return HotelInfoParsed(null, null, null)

        val paragraphs = sibling.select("p")
        val name = paragraphs.firstOrNull()?.select("strong")?.text()?.takeIf { it.isNotBlank() }
        val website = sibling.selectFirst("a[href^=http]")?.attr("href")

        // Address: all non-empty paragraphs between name and website, joined
        val addressParts = paragraphs.drop(1)
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("www.", ignoreCase = true) }
        val address = addressParts.joinToString(", ").takeIf { it.isNotBlank() }

        return HotelInfoParsed(name, address, website)
    }

    private fun parseHotelImages(content: Element, eventId: String): List<HotelImage> {
        // Hotel gallery is the slider gallery near the "Hotel" section
        // It's the first j-gallery with cc-m-gallery-slider class
        val sliderGallery = content.selectFirst(".cc-m-gallery-slider") ?: return emptyList()

        return sliderGallery.select("ul > li > a[data-href]").mapIndexed { idx, a ->
            val fullUrl = JimdoImageResolver.resolveFullResolution(a.attr("data-href"))
            HotelImage(
                originalUrl = fullUrl,
                localPath = "images/hotel/${String.format("%03d", idx + 1)}.jpg",
                sortOrder = idx
            )
        }
    }

    private fun parseDocuments(content: Element, eventId: String): List<CrawlerDocument> {
        return content.select(".j-downloadDocument").mapIndexed { idx, module ->
            val downloadLink = module.selectFirst("a.cc-m-download-link")
                ?: module.selectFirst("a.j-m-dowload")
            val href = downloadLink?.attr("href") ?: return@mapIndexed null

            val url = if (href.startsWith("http")) href else "$baseUrl$href"
            val title = module.selectFirst(".cc-m-download-title")?.text() ?: ""
            val size = module.selectFirst(".cc-m-download-file-size")?.text() ?: ""

            // Extract original filename from URL
            val rawFilename = href.substringAfterLast("/").substringBefore("?")
            val filename = try {
                URLDecoder.decode(rawFilename.replace("+", " "), "UTF-8")
            } catch (e: Exception) { rawFilename }

            val category = classifyDocument(title, filename)
            val localFilename = Slugify.slugify(filename.substringBeforeLast(".")) +
                "." + filename.substringAfterLast(".", "pdf")

            CrawlerDocument(
                title = title,
                filename = localFilename,
                category = category,
                originalUrl = url,
                localPath = "documents/$localFilename",
                sortOrder = idx,
                sizeDescription = size
            )
        }.filterNotNull()
    }

    private fun classifyDocument(title: String, filename: String): DocumentCategory {
        val lower = (title + " " + filename).lowercase()
        return when {
            lower.contains("convocation") -> DocumentCategory.CONVOCATION
            lower.contains("invitation") -> DocumentCategory.INVITATION
            lower.contains("agenda") -> DocumentCategory.AGENDA
            lower.contains("program") && !lower.contains("presentation") -> DocumentCategory.PROGRAM
            lower.contains("participant") -> DocumentCategory.PARTICIPANTS
            lower.contains("minutes") -> DocumentCategory.MINUTES
            lower.contains("sponsor") -> DocumentCategory.SPONSORING
            lower.contains("statut") || lower.contains("compliance") -> DocumentCategory.COMPLIANCE
            lower.contains("treasurer") || lower.contains("auditor") -> DocumentCategory.REPORT
            lower.contains("survey") || lower.contains("satisfaction") -> DocumentCategory.SURVEY
            lower.contains("presentation") || Regex("^\\d{8}\\s").containsMatchIn(lower) -> DocumentCategory.PRESENTATION
            else -> DocumentCategory.OTHER
        }
    }

    private fun parseVideos(content: Element): List<Video> {
        return content.select("iframe.cc-m-video-youtu-container").mapIndexedNotNull { idx, iframe ->
            val src = iframe.attr("data-src").takeIf { it.isNotBlank() }
                ?: iframe.attr("src").takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null

            val videoId = youtubeIdPattern.find(src)?.groupValues?.get(1) ?: return@mapIndexedNotNull null

            // Title: find preceding h3 in the same grid column or parent
            val title = findVideoTitle(iframe) ?: "Video ${idx + 1}"

            Video(
                title = title,
                youtubeUrl = "https://www.youtube.com/watch?v=$videoId",
                sortOrder = idx
            )
        }
    }

    private fun findVideoTitle(iframe: Element): String? {
        // Walk up to find the containing matrix, then look for h3
        var parent = iframe.parent()
        while (parent != null) {
            val id = parent.id()
            if (id.startsWith("cc-matrix-")) {
                val h3 = parent.selectFirst(".j-header h3")
                if (h3 != null) return h3.text()
            }
            if (parent.hasClass("cc-m-hgrid-column")) {
                val h3 = parent.selectFirst(".j-header h3")
                if (h3 != null) return h3.text()
            }
            parent = parent.parent()
        }
        return null
    }

    private fun parseGalleries(content: Element, eventId: String): List<Gallery> {
        // Find all gallery modules, skip the first slider gallery (hotel)
        val galleryModules = content.select(".j-gallery")
        val galleries = mutableListOf<Gallery>()

        for ((galleryIdx, galleryModule) in galleryModules.withIndex()) {
            val container = galleryModule.selectFirst(".cc-m-gallery-container") ?: continue

            // Skip slider galleries (hotel images) - they're handled separately
            if (container.hasClass("cc-m-gallery-slider")) continue

            // Find gallery title from preceding h3
            val title = findGalleryTitle(galleryModule) ?: "Gallery ${galleryIdx + 1}"
            val gallerySlug = Slugify.slugify(title)

            // Extract images from both "cool" and other variants
            val imageLinks = container.select("a[rel^=lightbox][data-href]")
            val images = imageLinks.mapIndexed { imgIdx, a ->
                val fullUrl = JimdoImageResolver.resolveFullResolution(a.attr("data-href"))
                GalleryImage(
                    originalUrl = fullUrl,
                    localPath = "images/$gallerySlug/${String.format("%03d", imgIdx + 1)}.jpg",
                    sortOrder = imgIdx
                )
            }

            if (images.isNotEmpty()) {
                galleries.add(Gallery(title = title, sortOrder = galleries.size, images = images))
            }
        }

        return galleries
    }

    private fun findGalleryTitle(galleryModule: Element): String? {
        // Walk backwards through siblings to find the nearest h3 header
        var sibling = galleryModule.previousElementSibling()
        while (sibling != null) {
            val h3 = sibling.selectFirst(".j-header h3")
            if (h3 != null) return h3.text()
            // Stop if we hit another gallery or a h2 section header
            if (sibling.hasClass("j-gallery") || sibling.selectFirst(".j-header h2") != null) break
            sibling = sibling.previousElementSibling()
        }
        return null
    }
}
