package de.febis.crawler.upload

import de.febis.crawler.model.*
import de.febis.crawler.util.Slugify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.File
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

class SupabaseUploader(
    private val client: SupabaseClient,
    private val eventDir: Path,
    private val maxParallelUploads: Int = 3,
    private val requestDelayMs: Long = 200,
    private val maxRetries: Int = 3
) {
    private val storageBucket = "event-images"
    private val semaphore = Semaphore(maxParallelUploads)

    suspend fun upload(event: Event, force: Boolean): CrawlerResult<Unit> {
        try {
            // 1. Check if event already exists
            val existing = client.select("events", "slug=eq.${event.id}&select=id")
            if (existing.isNotEmpty()) {
                if (!force) {
                    logger.info { "Event '${event.id}' already exists in Supabase. Use --force to overwrite." }
                    return CrawlerResult.Success(Unit)
                }
                val existingId = existing.first().jsonObject["id"]!!.jsonPrimitive.content
                logger.info { "Deleting existing event '${event.id}' (id=$existingId) ..." }
                client.delete("events", "id=eq.$existingId")
                // CASCADE deletes child rows; storage files are overwritten via x-upsert
            }

            // 2. Upload files to storage (parallel)
            val docUrls = uploadDocuments(event)
            val hotelUrls = uploadHotelImages(event)
            val galleryUrls = uploadGalleryImages(event)

            // 3. Insert DB records (sequential, FK order)
            val inserted = client.insert("events", SupabaseMapper.buildEventRow(event))
            val eventId = inserted["id"]!!.jsonPrimitive.content
            logger.info { "Inserted event '${event.id}' → $eventId" }

            // Documents
            if (event.documents.isNotEmpty()) {
                val rows = event.documents.map { doc ->
                    val url = docUrls[doc.localPath] ?: error("Missing upload URL for ${doc.localPath}")
                    SupabaseMapper.buildDocumentRow(doc, eventId, url)
                }
                client.insertBatch("event_documents", rows)
                logger.info { "Inserted ${rows.size} documents" }
            }

            // Videos
            if (event.videos.isNotEmpty()) {
                val rows = event.videos.map { SupabaseMapper.buildVideoRow(it, eventId) }
                client.insertBatch("event_videos", rows)
                logger.info { "Inserted ${rows.size} videos" }
            }

            // Hotel images
            if (event.hotelImages.isNotEmpty()) {
                val rows = event.hotelImages.map { img ->
                    val url = hotelUrls[img.localPath] ?: error("Missing upload URL for ${img.localPath}")
                    SupabaseMapper.buildHotelImageRow(eventId, url, img.sortOrder)
                }
                client.insertBatch("event_hotel_images", rows)
                logger.info { "Inserted ${rows.size} hotel images" }
            }

            // Galleries
            for (gallery in event.galleries) {
                val galleryInserted = client.insert("event_galleries", SupabaseMapper.buildGalleryRow(gallery, eventId))
                val galleryId = galleryInserted["id"]!!.jsonPrimitive.content
                logger.info { "Inserted gallery '${gallery.title}' → $galleryId" }

                if (gallery.images.isNotEmpty()) {
                    val rows = gallery.images.map { img ->
                        val url = galleryUrls[img.localPath] ?: error("Missing upload URL for ${img.localPath}")
                        SupabaseMapper.buildGalleryImageRow(galleryId, url, img.caption, img.sortOrder)
                    }
                    client.insertBatch("event_gallery_images", rows)
                    logger.info { "Inserted ${rows.size} gallery images for '${gallery.title}'" }
                }
            }

            logger.info { "Upload complete for event '${event.id}'" }
            return CrawlerResult.Success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Upload failed for event '${event.id}'" }
            return CrawlerResult.Failure(CrawlerError.SupabaseError("upload", e.message ?: "Unknown error"))
        }
    }

    private suspend fun uploadDocuments(event: Event): Map<String, String> = coroutineScope {
        logger.info { "Uploading ${event.documents.size} documents (max $maxParallelUploads parallel)" }
        event.documents.mapIndexed { idx, doc ->
            async {
                semaphore.withPermit {
                    delay(requestDelayMs)
                    val file = eventDir.resolve(doc.localPath).toFile()
                    val storagePath = "${event.id}/documents/${doc.filename}"
                    logger.info { "[${idx + 1}/${event.documents.size}] Uploading doc: ${doc.filename}" }
                    doc.localPath to uploadFileWithRetry(file, storagePath)
                }
            }
        }.awaitAll().toMap()
    }

    private suspend fun uploadHotelImages(event: Event): Map<String, String> = coroutineScope {
        logger.info { "Uploading ${event.hotelImages.size} hotel images (max $maxParallelUploads parallel)" }
        event.hotelImages.mapIndexed { idx, img ->
            async {
                semaphore.withPermit {
                    delay(requestDelayMs)
                    val file = eventDir.resolve(img.localPath).toFile()
                    val ext = file.extension.ifEmpty { "jpg" }
                    val storagePath = "${event.id}/hotel/${String.format("%03d", idx + 1)}.$ext"
                    logger.info { "[${idx + 1}/${event.hotelImages.size}] Uploading hotel image: ${file.name}" }
                    img.localPath to uploadFileWithRetry(file, storagePath)
                }
            }
        }.awaitAll().toMap()
    }

    private suspend fun uploadGalleryImages(event: Event): Map<String, String> = coroutineScope {
        val allImages = event.galleries.flatMap { it.images }
        logger.info { "Uploading ${allImages.size} gallery images (max $maxParallelUploads parallel)" }
        var counter = 0
        event.galleries.flatMap { gallery ->
            val gallerySlug = Slugify.slugify(gallery.title)
            gallery.images.mapIndexed { idx, img ->
                val num = ++counter
                async {
                    semaphore.withPermit {
                        delay(requestDelayMs)
                        val file = eventDir.resolve(img.localPath).toFile()
                        val ext = file.extension.ifEmpty { "jpg" }
                        val storagePath = "${event.id}/galleries/$gallerySlug/${String.format("%03d", idx + 1)}.$ext"
                        logger.info { "[$num/${allImages.size}] Uploading gallery image: ${gallery.title}/${file.name}" }
                        img.localPath to uploadFileWithRetry(file, storagePath)
                    }
                }
            }
        }.awaitAll().toMap()
    }

    private suspend fun uploadFileWithRetry(file: File, storagePath: String): String {
        if (!file.exists()) {
            logger.warn { "File not found: $file – skipped" }
            return ""
        }
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                return client.uploadFile(storageBucket, storagePath, file)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = requestDelayMs * attempt * 2
                    logger.warn { "Upload failed for ${file.name} (attempt $attempt/$maxRetries): ${e.message} – retrying in ${backoffMs}ms" }
                    delay(backoffMs)
                }
            }
        }
        logger.error { "Upload failed for ${file.name} after $maxRetries attempts: ${lastException?.message}" }
        throw lastException!!
    }
}
