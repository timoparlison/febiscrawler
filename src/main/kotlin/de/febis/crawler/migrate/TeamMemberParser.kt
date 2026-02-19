package de.febis.crawler.migrate

import de.febis.crawler.model.CrawlerError
import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.parser.JimdoImageResolver
import mu.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

private val logger = KotlinLogging.logger {}

class TeamMemberParser {

    private val roleKeywords = mapOf(
        "General Manager" to "general-manager",
        "Office Manager" to "office-manager",
        "Legal Counsel" to "legal-counsel",
        "Secretariat" to "secretariat"
    )

    private val sectionHeaders = setOf(
        "Administration", "Contact", "Download FEBIS Logo"
    )

    fun parse(html: String): CrawlerResult<List<TeamMemberData>> {
        return try {
            val doc = Jsoup.parse(html)
            val contentArea = doc.selectFirst("#content_area")
                ?: return CrawlerResult.Failure(
                    CrawlerError.ParseError("administration", "No #content_area found")
                )

            val members = mutableListOf<TeamMemberData>()
            var currentRole: String? = null
            val pendingImages = mutableListOf<String>()
            var sortOrder = 0

            // Walk through top-level modules only (direct children of the main matrix container)
            val mainMatrix = contentArea.selectFirst("[id^=cc-matrix]")
                ?: return CrawlerResult.Failure(
                    CrawlerError.ParseError("administration", "No cc-matrix container found")
                )
            val modules = mainMatrix.children().toList().filter { el ->
                el is Element && el.classNames().any { cls -> cls.startsWith("j-") }
            }.map { it as Element }

            for (module in modules) {
                when {
                    module.hasClass("j-header") -> {
                        val headerText = module.selectFirst("h2, h3")?.text()?.trim() ?: continue

                        // Check if it's a role header
                        val matchedRole = roleKeywords.entries.firstOrNull { (keyword, _) ->
                            headerText.equals(keyword, ignoreCase = true)
                        }

                        when {
                            matchedRole != null -> {
                                currentRole = matchedRole.value
                                logger.debug { "Found role section: $headerText → $currentRole" }
                            }
                            headerText in sectionHeaders -> {
                                // Non-person section, stop processing team members
                                currentRole = null
                                logger.debug { "Skipping section: $headerText" }
                            }
                            currentRole != null -> {
                                // This is a person name
                                val imageUrl = if (pendingImages.isNotEmpty()) pendingImages.removeFirst() else null
                                val member = TeamMemberData(
                                    name = headerText,
                                    role = currentRole,
                                    imageUrl = imageUrl,
                                    sortOrder = sortOrder++
                                )
                                members.add(member)
                                logger.debug { "Found team member: ${member.name} (${member.role})" }
                            }
                        }
                    }

                    module.hasClass("j-imageSubtitle") -> {
                        val imageUrl = extractImageUrl(module)
                        if (imageUrl != null) {
                            pendingImages.add(imageUrl)
                        }
                    }

                    module.hasClass("j-text") && currentRole != null && members.isNotEmpty() -> {
                        // Contact info belongs to the most recently added member
                        val lastMember = members.last()
                        val updated = parseContactInfo(module, lastMember)
                        members[members.lastIndex] = updated
                    }

                    module.hasClass("j-hgrid") -> {
                        // Grid modules contain nested modules (images or name+contact pairs)
                        parseGridModule(module, members, pendingImages, currentRole, sortOrder)
                        sortOrder = members.size
                    }
                }
            }

            CrawlerResult.Success(members)
        } catch (e: Exception) {
            CrawlerResult.Failure(
                CrawlerError.ParseError("administration", "Parse error: ${e.message}")
            )
        }
    }

    private fun parseGridModule(
        grid: Element,
        members: MutableList<TeamMemberData>,
        pendingImages: MutableList<String>,
        currentRole: String?,
        startSortOrder: Int
    ) {
        var sortOrder = startSortOrder
        val columns = grid.select(":root > .cc-m-hgrid-column")

        for (column in columns) {
            // Check for images in this column
            for (imgModule in column.select(".j-imageSubtitle")) {
                val imageUrl = extractImageUrl(imgModule)
                if (imageUrl != null) {
                    pendingImages.add(imageUrl)
                }
            }

            // Check for name headers + contact info in this column
            for (header in column.select(".j-header")) {
                val headerText = header.selectFirst("h2, h3")?.text()?.trim() ?: continue
                if (currentRole != null && headerText !in sectionHeaders
                    && roleKeywords.keys.none { it.equals(headerText, ignoreCase = true) }) {
                    val imageUrl = if (pendingImages.isNotEmpty()) pendingImages.removeFirst() else null
                    val member = TeamMemberData(
                        name = headerText,
                        role = currentRole,
                        imageUrl = imageUrl,
                        sortOrder = sortOrder++
                    )
                    members.add(member)
                    logger.debug { "Found team member in grid: ${member.name} (${member.role})" }
                }
            }

            // Check for contact info in this column
            for (textModule in column.select(".j-text")) {
                if (members.isNotEmpty()) {
                    val lastMember = members.last()
                    members[members.lastIndex] = parseContactInfo(textModule, lastMember)
                }
            }
        }
    }

    private fun extractImageUrl(module: Element): String? {
        // Try data-href first (lightbox link = higher quality)
        val lightboxLink = module.selectFirst("a[rel=lightbox][data-href]")
        if (lightboxLink != null) {
            return JimdoImageResolver.resolveFullResolution(lightboxLink.attr("data-href"))
        }

        // Try srcset or src from img
        val img = module.selectFirst("img") ?: return null
        val srcset = img.attr("srcset")
        if (srcset.isNotEmpty()) {
            // Get the highest resolution URL from srcset (last entry)
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

    private fun parseContactInfo(textModule: Element, member: TeamMemberData): TeamMemberData {
        var phone = member.phone
        var mobile = member.mobile
        var email = member.email

        for (p in textModule.select("p")) {
            val text = p.text().trim()
            when {
                text.startsWith("tel", ignoreCase = true) -> {
                    phone = text.substringAfter(":").trim().ifEmpty { null }
                }
                text.startsWith("cell", ignoreCase = true) -> {
                    mobile = text.substringAfter(":").trim().ifEmpty { null }
                }
                text.contains("email", ignoreCase = true) || p.selectFirst(".__cf_email__") != null -> {
                    // Try to decode Cloudflare-obfuscated email
                    val cfEmail = p.selectFirst(".__cf_email__[data-cfemail]")
                    if (cfEmail != null) {
                        email = decodeCfEmail(cfEmail.attr("data-cfemail"))
                    } else {
                        // Plain text email
                        email = text.substringAfter(":").trim().ifEmpty { null }
                    }
                }
            }
        }

        return member.copy(phone = phone, mobile = mobile, email = email)
    }

    companion object {
        /**
         * Decodes Cloudflare email obfuscation.
         * The first byte is the XOR key, subsequent bytes are XORed to produce the email.
         */
        fun decodeCfEmail(encoded: String): String? {
            if (encoded.length < 4) return null
            val key = encoded.substring(0, 2).toInt(16)
            val decoded = StringBuilder()
            var i = 2
            while (i < encoded.length) {
                val byte = encoded.substring(i, i + 2).toInt(16)
                decoded.append((byte xor key).toChar())
                i += 2
            }
            return decoded.toString().ifEmpty { null }
        }
    }
}
