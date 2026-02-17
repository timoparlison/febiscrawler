# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kotlin CLI tool that crawls the FEBIS Jimdo website (password-protected members area) to extract and archive all event data (metadata, PDFs, images, videos) into a structured local directory with JSON metadata. One-time migration tool, not a continuously running service.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run full migration
./gradlew run

# Run single event (for testing)
./gradlew run --args="--event 2024-nice"

# Dry run (index only)
./gradlew run --args="--dry-run"

# Resume after interruption
./gradlew run --args="--resume"

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

- **All method bodies are TODO stubs** – scaffold is complete, implementation needed
- Jimdo login is password-only (POST to `/members-login`, password: "torino")
- Jimdo image full-res: replace `dimension=\d+x\d+:?` with `none` in URL, strip `format=jpg/`
- YouTube IDs extracted from `<iframe>` embed URLs
- Downloads: max 5 parallel (Semaphore), 100ms delay between requests, 3 retries
- `migration-state.json` enables resume after interruption

## Documentation

- [Technische Dokumentation](docs/TECHNICAL.md) – Architecture, code conventions, Jimdo-specific patterns
- [Business Dokumentation](docs/BUSINESS.md) – FEBIS organization, data model, business rules

Always follow conventions from these docs: sealed classes for errors, data classes for DTOs, extension functions for Jsoup helpers, `KotlinLogging.logger {}` for logging.
