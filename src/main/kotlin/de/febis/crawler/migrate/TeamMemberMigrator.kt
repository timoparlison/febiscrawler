package de.febis.crawler.migrate

import de.febis.crawler.client.AuthenticatedSession
import de.febis.crawler.config.CrawlerConfig
import de.febis.crawler.model.CrawlerError
import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.parser.JimdoImageResolver
import de.febis.crawler.upload.SupabaseClient
import de.febis.crawler.upload.SupabaseMapper
import de.febis.crawler.util.Slugify
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

class TeamMemberMigrator(
    private val session: AuthenticatedSession,
    private val httpClient: HttpClient,
    private val supabaseClient: SupabaseClient,
    private val config: CrawlerConfig
) {
    private val storageBucket = "team-images"
    private val maxRetries = 3
    private val retryDelayMs = 400L

    suspend fun migrate(force: Boolean): CrawlerResult<Unit> {
        try {
            val adminUrl = "${config.baseUrl}${config.loginPath}/administration/"

            // 1. Fetch HTML
            logger.info { "Fetching administration page..." }
            val html = when (val result = session.fetchPage(adminUrl)) {
                is CrawlerResult.Success -> result.data
                is CrawlerResult.Failure -> return CrawlerResult.Failure(result.error)
            }

            // Save HTML for debugging
            val debugDir = config.outputDir.resolve("debug")
            Files.createDirectories(debugDir)
            Files.writeString(debugDir.resolve("administration.html"), html)
            logger.info { "Saved HTML to ${debugDir.resolve("administration.html")}" }

            // 2. Parse
            val parser = TeamMemberParser()
            val members = when (val result = parser.parse(html)) {
                is CrawlerResult.Success -> result.data
                is CrawlerResult.Failure -> return CrawlerResult.Failure(result.error)
            }

            if (members.isEmpty()) {
                logger.warn { "No team members found on page" }
                return CrawlerResult.Success(Unit)
            }

            logger.info { "Found ${members.size} team members:" }
            members.forEach { m ->
                logger.info { "  - ${m.name} (${m.role ?: "unknown role"})" }
                m.phone?.let { logger.info { "    tel: $it" } }
                m.mobile?.let { logger.info { "    cell: $it" } }
                m.email?.let { logger.info { "    email: $it" } }
                m.imageUrl?.let { logger.info { "    image: ${it.take(80)}..." } }
            }

            // 3. Force-delete existing entries if requested
            if (force) {
                logger.info { "Deleting existing team members..." }
                supabaseClient.delete("team_members", "id=neq.00000000-0000-0000-0000-000000000000")
            }

            // 4. Upload images and insert records
            val rows = members.map { member ->
                val imageUrl = if (member.imageUrl != null) {
                    uploadImage(member)
                } else null
                SupabaseMapper.buildTeamMemberRow(member, imageUrl)
            }

            supabaseClient.insertBatch("team_members", rows)
            logger.info { "Inserted ${rows.size} team members" }

            return CrawlerResult.Success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Team member migration failed" }
            return CrawlerResult.Failure(CrawlerError.SupabaseError("migrate-teammembers", e.message ?: "Unknown error"))
        }
    }

    private suspend fun uploadImage(member: TeamMemberData): String? {
        val imageUrl = member.imageUrl ?: return null
        val slug = Slugify.slugify(member.name)
        val storagePath = "$slug.jpg"

        for (attempt in 1..maxRetries) {
            try {
                // Download image from old site
                val response = httpClient.get(imageUrl)
                val bytes = response.readBytes()

                // Upload to Supabase Storage via temp file
                val tempFile = Files.createTempFile("team-img-", ".jpg")
                try {
                    tempFile.toFile().writeBytes(bytes)
                    val publicUrl = supabaseClient.uploadFile(storageBucket, storagePath, tempFile.toFile())
                    logger.info { "Uploaded image for ${member.name} → $publicUrl" }
                    return publicUrl
                } finally {
                    Files.deleteIfExists(tempFile)
                }
            } catch (e: Exception) {
                if (attempt < maxRetries) {
                    val backoff = retryDelayMs * attempt
                    logger.warn { "Image upload failed for ${member.name} (attempt $attempt/$maxRetries): ${e.message} – retrying in ${backoff}ms" }
                    delay(backoff)
                } else {
                    logger.error { "Image upload failed for ${member.name} after $maxRetries attempts: ${e.message}" }
                }
            }
        }
        return null
    }
}
