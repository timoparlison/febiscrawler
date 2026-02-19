package de.febis.crawler.migrate

import de.febis.crawler.config.CrawlerConfig
import de.febis.crawler.model.CrawlerError
import de.febis.crawler.model.CrawlerResult
import de.febis.crawler.upload.SupabaseClient
import de.febis.crawler.upload.SupabaseMapper
import de.febis.crawler.util.Slugify
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.nio.file.Files

private val logger = KotlinLogging.logger {}

class BoardMemberMigrator(
    private val httpClient: HttpClient,
    private val supabaseClient: SupabaseClient,
    private val config: CrawlerConfig
) {
    private val storageBucket = "board-images"
    private val maxRetries = 3
    private val retryDelayMs = 400L

    suspend fun migrate(force: Boolean): CrawlerResult<Unit> {
        try {
            val boardUrl = "${config.baseUrl}/about/executive-board/"

            // 1. Fetch HTML (public page, no auth needed)
            logger.info { "Fetching executive board page..." }
            val response = httpClient.get(boardUrl)
            val html = response.bodyAsText()
            logger.info { "Fetched ${html.length} chars" }

            // Save HTML for debugging
            val debugDir = config.outputDir.resolve("debug")
            Files.createDirectories(debugDir)
            Files.writeString(debugDir.resolve("executive-board.html"), html)
            logger.info { "Saved HTML to ${debugDir.resolve("executive-board.html")}" }

            // 2. Parse
            val parser = BoardMemberParser()
            val members = when (val result = parser.parse(html)) {
                is CrawlerResult.Success -> result.data
                is CrawlerResult.Failure -> return CrawlerResult.Failure(result.error)
            }

            if (members.isEmpty()) {
                logger.warn { "No board members found on page" }
                return CrawlerResult.Success(Unit)
            }

            logger.info { "Found ${members.size} board members:" }
            members.forEach { m ->
                logger.info { "  - ${m.name} (${m.role ?: "unknown role"})" }
                m.company?.let { logger.info { "    company: $it" } }
                m.location?.let { logger.info { "    location: $it" } }
                m.currentPositions?.let { logger.info { "    positions: ${it.replace("\n", " | ")}" } }
                m.profile?.let { logger.info { "    profile: ${it.take(80)}..." } }
                m.imageUrl?.let { logger.info { "    image: ${it.take(80)}..." } }
            }

            // 3. Force-delete existing entries if requested
            if (force) {
                logger.info { "Deleting existing board members..." }
                supabaseClient.delete("board_members", "id=neq.00000000-0000-0000-0000-000000000000")
            }

            // 4. Upload images and insert records
            val rows = members.map { member ->
                val imageUrl = if (member.imageUrl != null) {
                    uploadImage(member)
                } else null
                SupabaseMapper.buildBoardMemberRow(member, imageUrl)
            }

            supabaseClient.insertBatch("board_members", rows)
            logger.info { "Inserted ${rows.size} board members" }

            return CrawlerResult.Success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Board member migration failed" }
            return CrawlerResult.Failure(CrawlerError.SupabaseError("migrate-boardmembers", e.message ?: "Unknown error"))
        }
    }

    private suspend fun uploadImage(member: BoardMemberData): String? {
        val imageUrl = member.imageUrl ?: return null
        val slug = Slugify.slugify(member.name)
        val storagePath = "$slug.jpg"

        for (attempt in 1..maxRetries) {
            try {
                val response = httpClient.get(imageUrl)
                val bytes = response.readBytes()

                val tempFile = Files.createTempFile("board-img-", ".jpg")
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
