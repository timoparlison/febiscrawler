# Technische Dokumentation: FEBIS Crawler

## Tech Stack

- **Sprache:** Kotlin 1.9+
- **Build:** Gradle (Kotlin DSL)
- **HTTP Client:** Ktor Client
- **HTML Parsing:** Jsoup
- **JSON:** Kotlinx Serialization
- **Coroutines:** Kotlinx Coroutines (für parallele Downloads)
- **Logging:** SLF4J + Logback

## Projekt-Setup

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.21"
    application
}

dependencies {
    // HTTP
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    
    // HTML Parsing
    implementation("org.jsoup:jsoup:1.17.2")
    
    // JSON
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
}

application {
    mainClass.set("de.febis.crawler.MainKt")
}
```

## Architektur

```
src/main/kotlin/de/febis/crawler/
├── Main.kt                 # Einstiegspunkt, CLI-Argumente
├── config/
│   └── CrawlerConfig.kt    # Konfiguration (URLs, Credentials, Output-Pfad)
├── client/
│   ├── HttpClientFactory.kt    # Ktor Client Setup mit Cookie-Handling
│   └── AuthenticatedSession.kt # Login + Session-Management
├── parser/
│   ├── EventIndexParser.kt     # Parst die Übersichtsseite
│   ├── EventPageParser.kt      # Parst einzelne Event-Seiten
│   └── JimdoImageResolver.kt   # Extrahiert höchste Bildauflösung
├── downloader/
│   ├── FileDownloader.kt       # Download mit Retry-Logik
│   └── DownloadQueue.kt        # Parallele Downloads mit Rate Limiting
├── model/
│   └── Models.kt               # Alle data classes
├── output/
│   ├── OutputWriter.kt         # Schreibt JSON + Ordnerstruktur
│   └── MigrationLog.kt         # Protokolliert Fortschritt
└── util/
    └── Slugify.kt              # String zu URL-Slug
```

## Code Conventions

### Allgemein
- Keine Abkürzungen in Variablennamen (außer etablierte wie `url`, `id`)
- Data Classes für alle DTOs
- Sealed Classes für Fehlertypen
- Extension Functions für Jsoup-Hilfsmethoden

### Coroutines
```kotlin
// Parallele Downloads mit Semaphore für Rate Limiting
val semaphore = Semaphore(5)  // Max 5 parallele Downloads

suspend fun downloadAll(urls: List<String>) = coroutineScope {
    urls.map { url ->
        async {
            semaphore.withPermit {
                delay(100)  // 100ms zwischen Requests
                download(url)
            }
        }
    }.awaitAll()
}
```

### Error Handling
```kotlin
sealed class CrawlerResult<out T> {
    data class Success<T>(val data: T) : CrawlerResult<T>()
    data class Failure(val error: CrawlerError) : CrawlerResult<Nothing>()
}

sealed class CrawlerError {
    data class NetworkError(val url: String, val cause: Exception) : CrawlerError()
    data class ParseError(val url: String, val message: String) : CrawlerError()
    data class AuthError(val message: String) : CrawlerError()
}
```

### Logging
```kotlin
private val logger = KotlinLogging.logger {}

// Levels:
// - ERROR: Fehlgeschlagene Downloads, Parse-Fehler
// - WARN: Übersprungene Elemente, Retry-Versuche
// - INFO: Fortschritt (Event X von Y, Download-Statistik)
// - DEBUG: Einzelne URLs, Parse-Details
```

## HTTP Client Setup

```kotlin
fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpCookies) {
        storage = AcceptAllCookiesStorage()
    }
    
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        })
    }
    
    engine {
        requestTimeout = 30_000
        connectTimeout = 10_000
    }
    
    defaultRequest {
        headers {
            append(HttpHeaders.UserAgent, "FEBIS-Migration-Crawler/1.0")
        }
    }
}
```

## Jimdo-spezifische Patterns

### Login (Passwort-only)
```kotlin
// Jimdo Passwort-Seiten haben ein einfaches POST-Formular
suspend fun authenticate(client: HttpClient, loginUrl: String, password: String) {
    client.submitForm(
        url = loginUrl,
        formParameters = parameters {
            append("password", password)
        }
    )
    // Session-Cookie wird automatisch gespeichert
}
```

### Bild-URLs
```kotlin
// Jimdo Thumbnail: https://image.jimcdn.com/app/cms/image/transf/dimension=150x10000:format=jpg/...
// Vollbild:        https://image.jimcdn.com/app/cms/image/transf/none/...
// → "dimension=NxN" durch "none" ersetzen für Originalauflösung

fun resolveFullResolution(thumbnailUrl: String): String =
    thumbnailUrl.replace(Regex("dimension=\\d+x\\d+:?"), "none")
               .replace("format=jpg/", "")
```

### YouTube Embeds
```kotlin
// <iframe src="https://www.youtube.com/embed/VIDEO_ID?...">
fun extractYoutubeId(iframeSrc: String): String? =
    Regex("youtube\\.com/embed/([a-zA-Z0-9_-]+)").find(iframeSrc)?.groupValues?.get(1)
```

## Konfiguration

```kotlin
// config.properties oder Umgebungsvariablen
data class CrawlerConfig(
    val baseUrl: String,                    // https://xxx.jimdofree.com
    val loginPath: String = "/members-login",
    val password: String = "torino",
    val indexPath: String,                  // /general-assembly
    val outputDir: Path = Path("./output"),
    val maxParallelDownloads: Int = 5,
    val requestDelayMs: Long = 100,
    val maxRetries: Int = 3
)
```

## Fortschritts-Tracking

```kotlin
// migration-state.json - ermöglicht Fortsetzung nach Abbruch
data class MigrationState(
    val completedEvents: Set<String>,       // Event-IDs die fertig sind
    val failedDownloads: Map<String, Int>,  // URL → Anzahl Fehlversuche
    val lastRun: Instant
)
```

## CLI Interface

```bash
# Vollständige Migration
./gradlew run

# Einzelnes Event (zum Testen)
./gradlew run --args="--event 2024-nice"

# Nur Index laden (Dry Run)
./gradlew run --args="--dry-run"

# Fortsetzen nach Abbruch
./gradlew run --args="--resume"
```
