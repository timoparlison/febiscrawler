package de.febis.crawler.migrate

import de.febis.crawler.model.CrawlerError
import de.febis.crawler.model.CrawlerResult
import mu.KotlinLogging
import org.jsoup.Jsoup

private val logger = KotlinLogging.logger {}

class MemberCompanyParser {

    /**
     * Parses the members-by-company HTML page.
     *
     * Structure: single HTML table with 12 columns:
     *   0: spacer | 1: company name (link+bold) | 2: spacer | 3: country
     *   4: B2B information | 5: B2C information | 6: Compliance | 7: Debt collection
     *   8: Marketing services | 9: Credit management software | 10: Other | 11: spacer
     *
     * Data rows alternate with empty separator rows.
     * Service columns contain ✔ (checkmark) when the service applies.
     */
    fun parse(html: String): CrawlerResult<List<MemberCompanyData>> {
        return try {
            val doc = Jsoup.parse(html)
            val table = doc.selectFirst("#content_area table")
                ?: return CrawlerResult.Failure(
                    CrawlerError.ParseError("members-by-company", "No table found in #content_area")
                )

            val members = mutableListOf<MemberCompanyData>()
            var sortOrder = 0

            val rows = table.select("tr")
            logger.debug { "Found ${rows.size} table rows" }

            for (row in rows) {
                val cells = row.select("td")
                if (cells.size < 11) continue

                // Company name is in column 1 (0-indexed), inside <a><strong>
                val companyCell = cells[1]
                val link = companyCell.selectFirst("a[href]")
                val companyName = (link?.text() ?: companyCell.text()).trim()

                // Skip header row and empty separator rows
                if (companyName.isBlank() || companyName.equals("Company", ignoreCase = true)) continue

                // Country is in column 3
                val country = cells[3].text().trim()
                if (country.isBlank()) continue

                // Detail page URL
                val detailUrl = link?.attr("href")

                // Service flags: columns 4-10, check for ✔ character
                val b2b = hasCheckmark(cells[4])
                val b2c = hasCheckmark(cells[5])
                val compliance = hasCheckmark(cells[6])
                val debtCollection = hasCheckmark(cells[7])
                val marketing = hasCheckmark(cells[8])
                val creditSoftware = hasCheckmark(cells[9])
                val other = hasCheckmark(cells[10])

                val member = MemberCompanyData(
                    name = companyName,
                    country = country,
                    detailUrl = detailUrl,
                    b2bInformation = b2b,
                    b2cInformation = b2c,
                    compliance = compliance,
                    debtCollection = debtCollection,
                    marketingServices = marketing,
                    creditManagementSoftware = creditSoftware,
                    otherServices = other,
                    sortOrder = sortOrder++
                )
                members.add(member)
                logger.debug { "Parsed: ${member.name} (${member.country}) b2b=$b2b b2c=$b2c compliance=$compliance debt=$debtCollection marketing=$marketing creditSw=$creditSoftware other=$other" }
            }

            CrawlerResult.Success(members)
        } catch (e: Exception) {
            CrawlerResult.Failure(
                CrawlerError.ParseError("members-by-company", "Parse error: ${e.message}")
            )
        }
    }

    private fun hasCheckmark(cell: org.jsoup.nodes.Element): Boolean {
        val text = cell.text().trim()
        return text.contains("✔") || text.contains("✓") || text.contains("x", ignoreCase = false)
    }
}
