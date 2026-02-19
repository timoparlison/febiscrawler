package de.febis.crawler.migrate

import de.febis.crawler.model.CrawlerError
import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.parser.JimdoImageResolver
import mu.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

private val logger = KotlinLogging.logger {}

class BoardMemberParser {

    private val roleMapping = mapOf(
        "president" to "president",
        "vice president" to "vice-president",
        "treasurer" to "treasurer",
        "board member" to "board-member",
        "deputy board member" to "deputy-board-member"
    )

    fun parse(html: String): CrawlerResult<List<BoardMemberData>> {
        return try {
            val doc = Jsoup.parse(html)
            val contentArea = doc.selectFirst("#content_area")
                ?: return CrawlerResult.Failure(
                    CrawlerError.ParseError("executive-board", "No #content_area found")
                )

            val members = mutableListOf<BoardMemberData>()
            var sortOrder = 0

            // Each board member is in a j-textWithImage module
            val textWithImageModules = contentArea.select(".j-textWithImage")
            logger.debug { "Found ${textWithImageModules.size} textWithImage modules" }

            for (module in textWithImageModules) {
                val member = parseModule(module, sortOrder)
                if (member != null) {
                    members.add(member)
                    sortOrder++
                    logger.debug { "Parsed board member: ${member.name} (${member.role})" }
                }
            }

            CrawlerResult.Success(members)
        } catch (e: Exception) {
            CrawlerResult.Failure(
                CrawlerError.ParseError("executive-board", "Parse error: ${e.message}")
            )
        }
    }

    private fun parseModule(module: Element, sortOrder: Int): BoardMemberData? {
        val imageUrl = extractImageUrl(module)
        val textDiv = module.selectFirst(".cc-m-textwithimage-inline-rte") ?: return null

        // Extract role from red-colored text in h3
        val role = extractRole(textDiv)
        if (role == null) {
            logger.debug { "No role found in textWithImage module, skipping" }
            return null
        }

        // Parse paragraphs sequentially to extract structured data
        val paragraphs = textDiv.select("p")
        var name: String? = null
        var company: String? = null
        var location: String? = null
        var currentSection: String? = null
        val positions = mutableListOf<String>()
        val profileLines = mutableListOf<String>()

        for (p in paragraphs) {
            val text = p.text().trim()
            if (text.isBlank()) continue

            // Check for section headers (red colored text)
            val isRedHeader = p.selectFirst("[style*='color: #ff0000'], [style*='color: red']") != null
                    || p.selectFirst("span[style*='color: #ff0000'], span[style*='color: red']") != null
            if (isRedHeader && (text.contains("Current", ignoreCase = true) || text.contains("Position", ignoreCase = true))) {
                currentSection = "positions"
                continue
            }
            if (isRedHeader && text.contains("Profile", ignoreCase = true)) {
                currentSection = "profile"
                continue
            }
            if (isRedHeader && text.contains("Ambition", ignoreCase = true)) {
                currentSection = "ambition"
                continue
            }

            // Skip "Click here to read more..." links
            if (text.contains("Click here", ignoreCase = true)) continue

            when (currentSection) {
                null -> {
                    // Before any section: name, company, location
                    if (name == null) {
                        // Name is typically bold and 20px
                        val hasBold = p.selectFirst("strong, b, [style*='font-weight: 700']") != null
                        val has20px = p.selectFirst("[style*='font-size: 20px']") != null
                        if (hasBold && has20px) {
                            // Could contain name + company on same line (separated by <br>)
                            val html = p.html()
                            if (html.contains("<br")) {
                                val parts = html.split(Regex("<br\\s*/?>"))
                                name = Jsoup.parse(parts[0]).text().trim()
                                if (parts.size > 1) {
                                    company = Jsoup.parse(parts[1]).text().trim()
                                }
                            } else {
                                name = text
                            }
                        }
                    } else if (company == null) {
                        company = text
                    } else if (location == null) {
                        location = text
                    }
                }
                "positions" -> positions.add(text)
                "profile" -> profileLines.add(text)
                "ambition" -> {} // Not commonly used, could be added later
            }
        }

        if (name == null) {
            logger.debug { "No name found in textWithImage module with role $role" }
            return null
        }

        return BoardMemberData(
            name = name,
            role = role,
            company = company,
            location = location,
            currentPositions = if (positions.isNotEmpty()) positions.joinToString("\n") else null,
            profile = if (profileLines.isNotEmpty()) profileLines.joinToString("\n") else null,
            imageUrl = imageUrl,
            sortOrder = sortOrder
        )
    }

    private fun extractRole(textDiv: Element): String? {
        // Role is in an h3 element with red text
        for (h3 in textDiv.select("h3")) {
            val redSpan = h3.selectFirst("[style*='color: #ff0000'], [style*='color: red']")
            if (redSpan != null) {
                val roleText = redSpan.text().trim().lowercase()
                val mapped = roleMapping.entries.firstOrNull { (key, _) ->
                    roleText.equals(key, ignoreCase = true)
                }?.value
                if (mapped != null) return mapped
            }
        }
        return null
    }

    private fun extractImageUrl(module: Element): String? {
        // Try data-href first (lightbox = full resolution)
        val lightboxLink = module.selectFirst("a[rel=lightbox][data-href]")
        if (lightboxLink != null) {
            return JimdoImageResolver.resolveFullResolution(lightboxLink.attr("data-href"))
        }

        // Try srcset (last entry = highest resolution)
        val img = module.selectFirst("img") ?: return null
        val srcset = img.attr("srcset")
        if (srcset.isNotEmpty()) {
            val lastEntry = srcset.split(",").lastOrNull()?.trim()?.split(" ")?.firstOrNull()
            if (lastEntry != null) {
                return JimdoImageResolver.resolveFullResolution(lastEntry)
            }
        }

        val src = img.attr("src")
        if (src.isNotEmpty()) {
            return JimdoImageResolver.resolveFullResolution(src)
        }
        return null
    }
}
