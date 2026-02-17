# FEBIS Crawled Data

This directory contains structured data crawled from the FEBIS (Federation of European Business Information Services) members-only website. It serves as the data source for the new FEBIS Connect platform.

## Directory Structure

```
crawledData/
  CLAUDE.md                          # This file
  schema/
    event.schema.json                # JSON Schema for event.json files
  events/
    {event-id}/                      # e.g. 2025-rhodes, 2024-nice
      event.json                     # All event metadata (see schema below)
      documents/                     # Downloaded PDFs (presentations, agendas, etc.)
        {slugified-filename}.pdf
      images/                        # All downloaded images (hotel + galleries)
        hotel/
          001.jpg, 002.jpg, ...
        {gallery-slug}/              # e.g. gala-dinner, september-25
          001.jpg, 002.jpg, ...
```

## event.json Structure

Each event directory contains an `event.json` with the complete event metadata. All file references use **relative paths** from the event directory.

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | URL slug identifier, e.g. `"2025-rhodes"` |
| `title` | string | Display title from the website, e.g. `"2025 Rhodes"` |
| `dateRange` | string? | Date range as displayed, e.g. `"24.09.2025 - 26.09.2025"` |
| `location` | string? | City/venue name |
| `sourceUrl` | string | Original URL on the FEBIS website |
| `crawledAt` | string | ISO 8601 timestamp of when this data was crawled |
| `hotelInfo` | object? | Hotel details (see below) |
| `documents` | array | List of PDF documents (see below) |
| `videos` | array | List of YouTube video references (see below) |
| `galleries` | array | List of photo galleries (see below) |

### hotelInfo

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Hotel name |
| `address` | string | Full address |
| `websiteUrl` | string? | Hotel website URL |
| `images` | string[] | Relative paths to hotel images, e.g. `["images/hotel/001.jpg"]` |

### documents[]

| Field | Type | Description |
|-------|------|-------------|
| `title` | string | Document title as displayed on the website |
| `filename` | string | Local filename in the `documents/` folder |
| `category` | string | One of: `CONVOCATION`, `INVITATION`, `AGENDA`, `PROGRAM`, `PARTICIPANTS`, `PRESENTATION`, `REPORT`, `SURVEY`, `SPONSORING`, `COMPLIANCE`, `MINUTES`, `OTHER` |
| `originalUrl` | string | Original download URL |
| `localPath` | string | Relative path, e.g. `"documents/agenda-2025.pdf"` |
| `sizeDescription` | string | Human-readable file size, e.g. `"418.1 KB"` |

### videos[]

| Field | Type | Description |
|-------|------|-------------|
| `title` | string | Video title |
| `youtubeUrl` | string | Full YouTube URL |
| `youtubeId` | string | YouTube video ID for embedding |

### galleries[]

| Field | Type | Description |
|-------|------|-------------|
| `title` | string | Gallery section title, e.g. `"Gala dinner - Le Negresco"` |
| `sortOrder` | int | Display order (0-based, as on the original page) |
| `images` | array | List of gallery images (see below) |

### galleries[].images[]

| Field | Type | Description |
|-------|------|-------------|
| `originalUrl` | string | Original full-resolution image URL |
| `localPath` | string | Relative path, e.g. `"images/gala-dinner/001.jpg"` |
| `sortOrder` | int | Display order within the gallery (0-based) |

## Data Characteristics

- **~70 events** spanning 2009-2025, each a FEBIS General Assembly
- Older events (pre-2015) may have fewer fields (no videos, smaller galleries)
- Virtual events (2020-2022) typically have no hotel info or galleries
- Document categories are inferred from titles; `OTHER` is the fallback
- All images are downloaded at full resolution
- Gallery images are numbered sequentially per gallery (001.jpg, 002.jpg, ...)

## Usage Notes

- All file paths in `event.json` are **relative to the event directory**
- Nullable fields are omitted from JSON when not available (not set to `null`)
- The `id` field matches the directory name
- YouTube videos are not downloaded, only referenced by URL/ID
