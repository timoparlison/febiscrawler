package de.febis.crawler.model

import kotlinx.serialization.Serializable

@Serializable
data class Event(
    val id: String,
    val title: String,
    val dateRange: String?,
    val location: String?,
    val hotelInfo: HotelInfo?,
    val documents: List<Document>,
    val videos: List<Video>,
    val galleries: List<Gallery>
)

@Serializable
data class HotelInfo(
    val name: String,
    val address: String,
    val websiteUrl: String?,
    val images: List<String>
)

@Serializable
data class Document(
    val title: String,
    val filename: String,
    val category: DocumentCategory,
    val originalUrl: String,
    val localPath: String,
    val sizeDescription: String
)

@Serializable
enum class DocumentCategory {
    CONVOCATION,
    INVITATION,
    AGENDA,
    PROGRAM,
    PARTICIPANTS,
    PRESENTATION,
    REPORT,
    SURVEY,
    SPONSORING,
    COMPLIANCE,
    MINUTES,
    OTHER
}

@Serializable
data class Video(
    val title: String,
    val youtubeUrl: String,
    val youtubeId: String
)

@Serializable
data class Gallery(
    val title: String,
    val sortOrder: Int,
    val images: List<GalleryImage>
)

@Serializable
data class GalleryImage(
    val originalUrl: String,
    val localPath: String,
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
