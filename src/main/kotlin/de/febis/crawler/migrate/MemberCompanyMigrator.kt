package de.febis.crawler.migrate

import de.febis.crawler.config.CrawlerConfig
import de.febis.crawler.model.CrawlerError
import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.upload.SupabaseClient
import de.febis.crawler.upload.SupabaseMapper
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import mu.KotlinLogging
import java.nio.file.Files

private val logger = KotlinLogging.logger {}

class MemberCompanyMigrator(
    private val httpClient: HttpClient,
    private val supabaseClient: SupabaseClient,
    private val config: CrawlerConfig
) {
    suspend fun migrate(force: Boolean): CrawlerResult<Unit> {
        try {
            val membersUrl = "${config.baseUrl}/membership/members-by-company/"

            // 1. Fetch HTML (public page, no auth needed)
            logger.info { "Fetching members-by-company page..." }
            val response = httpClient.get(membersUrl)
            val html = response.bodyAsText()
            logger.info { "Fetched ${html.length} chars" }

            // Save HTML for debugging
            val debugDir = config.outputDir.resolve("debug")
            Files.createDirectories(debugDir)
            Files.writeString(debugDir.resolve("members-by-company.html"), html)
            logger.info { "Saved HTML to ${debugDir.resolve("members-by-company.html")}" }

            // 2. Parse
            val parser = MemberCompanyParser()
            val companies = when (val result = parser.parse(html)) {
                is CrawlerResult.Success -> result.data
                is CrawlerResult.Failure -> return CrawlerResult.Failure(result.error)
            }

            if (companies.isEmpty()) {
                logger.warn { "No member companies found on page" }
                return CrawlerResult.Success(Unit)
            }

            logger.info { "Found ${companies.size} member companies:" }
            companies.forEach { c ->
                logger.info { "  - ${c.name} (${c.country})" }
            }

            // 3. Validate ALL countries can be mapped BEFORE inserting anything
            val allCountryLabels = companies.map { it.country }.filter { it.isNotBlank() }.toSet()
            val unmappable = CountryMapper.findUnmappableCountries(allCountryLabels)

            if (unmappable.isNotEmpty()) {
                val message = buildString {
                    appendLine("Country mapping failed! The following countries could not be mapped to ISO codes:")
                    appendLine()
                    unmappable.sorted().forEach { label ->
                        appendLine("  Label: \"$label\" → Value: ??? (no mapping found)")
                    }
                    appendLine()
                    appendLine("Add these mappings to CountryMapper before retrying.")
                }
                logger.error { message }
                return CrawlerResult.Failure(
                    CrawlerError.ParseError("members-by-company", message)
                )
            }

            // Also check for companies without any country
            val noCountry = companies.filter { it.country.isBlank() }
            if (noCountry.isNotEmpty()) {
                val message = buildString {
                    appendLine("${noCountry.size} companies have no country assigned:")
                    noCountry.forEach { appendLine("  - ${it.name}") }
                    appendLine()
                    appendLine("Check the HTML parser – every company needs a country.")
                }
                logger.error { message }
                return CrawlerResult.Failure(
                    CrawlerError.ParseError("members-by-company", message)
                )
            }

            logger.info { "All ${allCountryLabels.size} countries mapped successfully:" }
            allCountryLabels.sorted().forEach { label ->
                logger.info { "  \"$label\" → ${CountryMapper.toCode(label)}" }
            }

            // 4. Force-delete existing entries if requested
            if (force) {
                logger.info { "Deleting existing companies..." }
                supabaseClient.delete("companies", "id=neq.00000000-0000-0000-0000-000000000000")
            }

            // 5. Insert records
            val rows = companies.map { company ->
                val countryCode = CountryMapper.toCode(company.country)!!
                SupabaseMapper.buildCompanyRow(company, countryCode)
            }

            supabaseClient.insertBatch("companies", rows)
            logger.info { "Inserted ${rows.size} member companies" }

            return CrawlerResult.Success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Member company migration failed" }
            return CrawlerResult.Failure(
                CrawlerError.SupabaseError("migrate-companies", e.message ?: "Unknown error")
            )
        }
    }
}
