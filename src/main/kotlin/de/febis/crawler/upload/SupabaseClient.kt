package de.febis.crawler.upload

import de.febis.crawler.config.CrawlerConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

class SupabaseClient(
    private val httpClient: HttpClient,
    private val config: CrawlerConfig
) {
    private val baseUrl = "https://${config.supabaseProjectId}.supabase.co"
    private val apiKey = config.supabaseServiceRoleKey!!

    suspend fun select(table: String, query: String): JsonArray {
        val response = httpClient.get("$baseUrl/rest/v1/$table?$query") {
            header("apikey", apiKey)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        check(response.status.isSuccess()) {
            "SELECT $table?$query failed: ${response.status} – ${response.bodyAsText()}"
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonArray
    }

    suspend fun insert(table: String, body: JsonObject): JsonObject {
        val response = httpClient.post("$baseUrl/rest/v1/$table") {
            header("apikey", apiKey)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        check(response.status.isSuccess()) {
            "INSERT $table failed: ${response.status} – ${response.bodyAsText()}"
        }
        val arr = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        return arr.first().jsonObject
    }

    suspend fun insertBatch(table: String, rows: List<JsonObject>): JsonArray {
        if (rows.isEmpty()) return JsonArray(emptyList())
        val body = JsonArray(rows)
        val response = httpClient.post("$baseUrl/rest/v1/$table") {
            header("apikey", apiKey)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        check(response.status.isSuccess()) {
            "INSERT $table (batch ${rows.size}) failed: ${response.status} – ${response.bodyAsText()}"
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonArray
    }

    suspend fun delete(table: String, query: String) {
        val response = httpClient.delete("$baseUrl/rest/v1/$table?$query") {
            header("apikey", apiKey)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        check(response.status.isSuccess()) {
            "DELETE $table?$query failed: ${response.status} – ${response.bodyAsText()}"
        }
    }

    suspend fun uploadFile(bucket: String, path: String, file: File): String {
        val contentType = guessContentType(file.name)
        val response = httpClient.post("$baseUrl/storage/v1/object/$bucket/$path") {
            header("apikey", apiKey)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, contentType)
            header("x-upsert", "true")
            setBody(file.readBytes())
        }
        check(response.status.isSuccess()) {
            "UPLOAD $bucket/$path failed: ${response.status} – ${response.bodyAsText()}"
        }
        return "$baseUrl/storage/v1/object/public/$bucket/$path"
    }

    private fun guessContentType(filename: String): String = when {
        filename.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
        filename.endsWith(".jpg", ignoreCase = true) || filename.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        filename.endsWith(".png", ignoreCase = true) -> "image/png"
        filename.endsWith(".gif", ignoreCase = true) -> "image/gif"
        filename.endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> "application/octet-stream"
    }
}
