package de.febis.crawler.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Event(
    val id: String,
    val title: String,
    val eventType: String = "general-assembly",
    val dateStart: String? = null,
    val dateEnd: String? = null,
    val locationCity: String? = null,
    val locationCountry: String? = null,
    val description: String? = null,
    val sourceUrl: String,
    val crawledAt: String,
    val hotelName: String? = null,
    val hotelAddress: String? = null,
    val hotelWebsite: String? = null,
    val hotelImages: List<HotelImage> = emptyList(),
    val documents: List<Document> = emptyList(),
    val videos: List<Video> = emptyList(),
    val galleries: List<Gallery> = emptyList()
)

@Serializable
data class HotelImage(
    val originalUrl: String,
    val localPath: String,
    val sortOrder: Int
)

@Serializable
data class Document(
    val title: String,
    val filename: String,
    val category: DocumentCategory,
    val originalUrl: String,
    val localPath: String,
    val sortOrder: Int,
    val sizeDescription: String = ""
)

@Serializable
enum class DocumentCategory {
    @SerialName("convocation") CONVOCATION,
    @SerialName("invitation") INVITATION,
    @SerialName("agenda") AGENDA,
    @SerialName("program") PROGRAM,
    @SerialName("participants") PARTICIPANTS,
    @SerialName("presentation") PRESENTATION,
    @SerialName("report") REPORT,
    @SerialName("survey") SURVEY,
    @SerialName("sponsoring") SPONSORING,
    @SerialName("compliance") COMPLIANCE,
    @SerialName("minutes") MINUTES,
    @SerialName("other") OTHER
}

@Serializable
data class Video(
    val title: String,
    val youtubeUrl: String,
    val sortOrder: Int
)

@Serializable
data class Gallery(
    val title: String,
    val sortOrder: Int,
    val images: List<GalleryImage> = emptyList()
)

@Serializable
data class GalleryImage(
    val originalUrl: String,
    val localPath: String,
    val caption: String? = null,
    val sortOrder: Int
)

// Result type for error handling
sealed class CrawlerResult<out T> {
    data class Success<T>(val data: T) : CrawlerResult<T>()
    data class Failure(val error: CrawlerError) : CrawlerResult<Nothing>()
}

sealed class CrawlerError {
    data class NetworkError(val url: String, val cause: Exception) : CrawlerError()
    data class ParseError(val url: String, val message: String) : CrawlerError()
    data class AuthError(val message: String) : CrawlerError()
    data class SupabaseError(val operation: String, val message: String) : CrawlerError()
}

// Index entry for events-index.json
@Serializable
data class EventIndexEntry(
    val id: String,
    val title: String,
    val url: String
)

// Migration state for resumable crawling
@Serializable
data class MigrationState(
    val completedEvents: Set<String>,
    val failedDownloads: Map<String, Int>,
    val lastRun: String
)
