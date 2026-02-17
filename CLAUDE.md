# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kotlin CLI tool that crawls the FEBIS Jimdo website (password-protected members area) to extract and archive all event data (metadata, PDFs, images, videos) into a structured local directory with JSON metadata. One-time migration tool, not a continuously running service.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run full migration (skips already-crawled events)
./gradlew run

# Run single event
./gradlew run --args="--event 2025-rhodes"

# Re-download an already-crawled event
./gradlew run --args="--event 2025-rhodes --force"

# Dry run (list events and crawl status)
./gradlew run --args="--dry-run"

# Run tests
./gradlew test
```

## Architecture

Kotlin 1.9 / JVM 21 / Gradle Kotlin DSL. Entry point: `de.febis.crawler.MainKt`.

**Pipeline:** Config → Ktor HTTP Client + Auth → Jsoup HTML Parsing → Parallel File Downloads → JSON + File Output

Key packages under `src/main/kotlin/de/febis/crawler/`:
- `config/` – `CrawlerConfig` data class (env vars `FEBIS_BASE_URL`, `FEBIS_INDEX_PATH`)
- `client/` – Ktor client factory with cookie handling; Jimdo password-only auth (POST form, no username)
- `parser/` – Index page parser, event page parser, Jimdo image URL resolver (`dimension=NxN` → `none`)
- `downloader/` – Single file download with retry; parallel queue with Semaphore(5) rate limiting
- `model/` – All `@Serializable` data classes, sealed `CrawlerResult`/`CrawlerError` types
- `output/` – JSON writer (per-event `meta.json`, `events-index.json`), resumable `MigrationState` tracking
- `util/` – Slugify helper

## Key Technical Details

- Jimdo login: POST `password` + `do_login=yes` to the target page URL (not a separate login endpoint)
- Jimdo image full-res: strip `/cdn-cgi/image/{params}` from CDN URLs
- YouTube IDs extracted from `<iframe>` embed URLs (`data-src` attribute)
- Downloads: max 5 parallel (Semaphore), 100ms delay between requests, 3 retries
- Skip-check: event is considered crawled if `crawledData/events/{id}/event.json` exists
- `--force` flag overrides skip-check for single-event mode

## Documentation

- [Technische Dokumentation](docs/TECHNICAL.md) – Architecture, code conventions, Jimdo-specific patterns
- [Business Dokumentation](docs/BUSINESS.md) – FEBIS organization, data model, business rules

Always follow conventions from these docs: sealed classes for errors, data classes for DTOs, extension functions for Jsoup helpers, `KotlinLogging.logger {}` for logging.
