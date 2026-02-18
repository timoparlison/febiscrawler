package de.febis.crawler.upload

import de.febis.crawler.model.*
import kotlinx.serialization.json.*

object SupabaseMapper {

    fun buildEventRow(event: Event): JsonObject = buildJsonObject {
        put("title", event.title)
        put("slug", event.id)
        put("event_type", event.eventType)
        put("status", "draft")
        put("date_start", event.dateStart?.let { JsonPrimitive(it) } ?: JsonNull)
        put("date_end", event.dateEnd?.let { JsonPrimitive(it) } ?: JsonNull)
        put("location_city", event.locationCity?.let { JsonPrimitive(it) } ?: JsonNull)
        put("location_country", event.locationCountry?.let { JsonPrimitive(it) } ?: JsonNull)
        put("description", event.description?.let { JsonPrimitive(it) } ?: JsonNull)
        put("hotel_name", event.hotelName?.let { JsonPrimitive(it) } ?: JsonNull)
        put("hotel_address", event.hotelAddress?.let { JsonPrimitive(it) } ?: JsonNull)
        put("hotel_website", event.hotelWebsite?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    fun buildDocumentRow(doc: Document, eventId: String, fileUrl: String): JsonObject = buildJsonObject {
        put("event_id", eventId)
        put("title", doc.title)
        put("filename", doc.filename)
        put("file_url", fileUrl)
        put("category", doc.category.name.lowercase())
        put("sort_order", doc.sortOrder)
    }

    fun buildVideoRow(video: Video, eventId: String): JsonObject = buildJsonObject {
        put("event_id", eventId)
        put("title", video.title)
        put("youtube_url", video.youtubeUrl)
        put("sort_order", video.sortOrder)
    }

    fun buildHotelImageRow(eventId: String, imageUrl: String, sortOrder: Int): JsonObject = buildJsonObject {
        put("event_id", eventId)
        put("image_url", imageUrl)
        put("sort_order", sortOrder)
    }

    fun buildGalleryRow(gallery: Gallery, eventId: String): JsonObject = buildJsonObject {
        put("event_id", eventId)
        put("title", gallery.title)
        put("sort_order", gallery.sortOrder)
    }

    fun buildGalleryImageRow(galleryId: String, imageUrl: String, caption: String?, sortOrder: Int): JsonObject = buildJsonObject {
        put("gallery_id", galleryId)
        put("image_url", imageUrl)
        put("caption", caption?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sort_order", sortOrder)
    }
}
