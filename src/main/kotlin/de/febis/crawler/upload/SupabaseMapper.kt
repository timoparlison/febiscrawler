package de.febis.crawler.upload

import de.febis.crawler.migrate.BoardMemberData
import de.febis.crawler.migrate.TeamMemberData
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

    fun buildBoardMemberRow(member: BoardMemberData, imageUrl: String?): JsonObject = buildJsonObject {
        put("name", member.name)
        put("role", member.role?.let { JsonPrimitive(it) } ?: JsonNull)
        put("company", member.company?.let { JsonPrimitive(it) } ?: JsonNull)
        put("location", member.location?.let { JsonPrimitive(it) } ?: JsonNull)
        put("current_positions", member.currentPositions?.let { JsonPrimitive(it) } ?: JsonNull)
        put("profile", member.profile?.let { JsonPrimitive(it) } ?: JsonNull)
        put("ambition", member.ambition?.let { JsonPrimitive(it) } ?: JsonNull)
        put("linkedin_url", member.linkedinUrl?.let { JsonPrimitive(it) } ?: JsonNull)
        put("image_url", imageUrl?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sort_order", member.sortOrder)
    }

    fun buildTeamMemberRow(member: TeamMemberData, imageUrl: String?): JsonObject = buildJsonObject {
        put("name", member.name)
        put("role", member.role?.let { JsonPrimitive(it) } ?: JsonNull)
        put("phone", member.phone?.let { JsonPrimitive(it) } ?: JsonNull)
        put("mobile", member.mobile?.let { JsonPrimitive(it) } ?: JsonNull)
        put("email", member.email?.let { JsonPrimitive(it) } ?: JsonNull)
        put("linkedin_url", member.linkedinUrl?.let { JsonPrimitive(it) } ?: JsonNull)
        put("image_url", imageUrl?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sort_order", member.sortOrder)
    }
}
