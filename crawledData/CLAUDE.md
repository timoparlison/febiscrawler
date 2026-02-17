# FEBIS Crawled Data

This directory contains structured data crawled from the FEBIS (Federation of European Business Information Services) members-only Jimdo website. It serves as the data source for importing into the **febis-connect** Supabase database.

## Directory Structure

```
crawledData/
  CLAUDE.md                          # This file
  schema/
    event.schema.json                # JSON Schema for event.json files
  events/
    {event-id}/                      # e.g. 2025-rhodes, 2024-nice
      event.json                     # All event metadata (see schema)
      documents/                     # Downloaded PDFs
        {slugified-filename}.pdf
      images/                        # All downloaded images
        hotel/
          001.jpg, 002.jpg, ...
        {gallery-slug}/              # e.g. gala-dinner, september-25
          001.jpg, 002.jpg, ...
```

## Supabase Target Mapping

The JSON field names are designed to map directly to the febis-connect Supabase schema:

| event.json field | Supabase table.column |
|---|---|
| `id` | `events.slug` |
| `title` | `events.title` |
| `eventType` | `events.event_type` |
| `dateStart` / `dateEnd` | `events.date_start` / `events.date_end` |
| `locationCity` / `locationCountry` | `events.location_city` / `events.location_country` |
| `description` | `events.description` |
| `hotelName` / `hotelAddress` / `hotelWebsite` | `events.hotel_name` / `events.hotel_address` / `events.hotel_website` |
| `hotelImages[]` | `event_hotel_images` rows |
| `documents[]` | `event_documents` rows |
| `videos[]` | `event_videos` rows |
| `galleries[]` | `event_galleries` rows |
| `galleries[].images[]` | `event_gallery_images` rows |

### Image Storage

Images should be uploaded to Supabase Storage bucket `event-images`:
- Hotel images → `{eventId}/hotel/{filename}`
- Gallery images → `{eventId}/gallery/{galleryId}/{filename}`

After upload, update the `image_url` / `file_url` fields with the Supabase public URL.

## event.json Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | yes | URL slug, e.g. `"2025-rhodes"` |
| `title` | string | yes | Display title |
| `eventType` | string | yes | `"general-assembly"`, `"spring-meeting"`, etc. |
| `dateStart` | string | no | ISO date `YYYY-MM-DD` |
| `dateEnd` | string | no | ISO date `YYYY-MM-DD` |
| `locationCity` | string | no | City name |
| `locationCountry` | string | no | ISO 3166-1 alpha-2 code (`"GR"`, `"FR"`, `"DE"`) |
| `description` | string | no | Event description text |
| `sourceUrl` | string | yes | Original FEBIS website URL |
| `crawledAt` | string | yes | ISO 8601 timestamp |
| `hotelName` | string | no | Hotel name |
| `hotelAddress` | string | no | Hotel full address |
| `hotelWebsite` | string | no | Hotel website URL |
| `hotelImages` | array | no | Hotel images (see below) |
| `documents` | array | no | PDF documents (see below) |
| `videos` | array | no | YouTube references (see below) |
| `galleries` | array | no | Photo galleries (see below) |

### hotelImages[]

| Field | Type | Description |
|-------|------|-------------|
| `originalUrl` | string | Source URL from Jimdo |
| `localPath` | string | Relative path, e.g. `"images/hotel/001.jpg"` |
| `sortOrder` | int | Display order (0-based) |

### documents[]

| Field | Type | Description |
|-------|------|-------------|
| `title` | string | Document title as displayed |
| `filename` | string | Local filename in `documents/` |
| `category` | string | `convocation`, `invitation`, `agenda`, `program`, `participants`, `presentation`, `report`, `survey`, `sponsoring`, `compliance`, `minutes`, `other` |
| `originalUrl` | string | Original download URL |
| `localPath` | string | Relative path, e.g. `"documents/agenda-2025.pdf"` |
| `sortOrder` | int | Display order (0-based) |
| `sizeDescription` | string | Human-readable, e.g. `"418.1 KB"` |

### videos[]

| Field | Type | Description |
|-------|------|-------------|
| `title` | string | Video title |
| `youtubeUrl` | string | Full YouTube URL |
| `sortOrder` | int | Display order (0-based) |

### galleries[]

| Field | Type | Description |
|-------|------|-------------|
| `title` | string | Gallery section title |
| `sortOrder` | int | Display order (0-based) |
| `images` | array | Gallery images (see below) |

### galleries[].images[]

| Field | Type | Description |
|-------|------|-------------|
| `originalUrl` | string | Full-resolution image URL |
| `localPath` | string | Relative path, e.g. `"images/gala-dinner/001.jpg"` |
| `caption` | string? | Image caption if available |
| `sortOrder` | int | Order within gallery (0-based) |

## Data Characteristics

- ~70 events spanning 2009-2025 (FEBIS General Assemblies)
- Older events (pre-2015) may have fewer fields
- Virtual events (2020-2022) typically have no hotel info or galleries
- Document categories are inferred from titles; `other` is the fallback
- All images downloaded at full resolution from Jimdo CDN
- Gallery images numbered sequentially per gallery (001.jpg, 002.jpg, ...)
- Nullable fields are omitted from JSON when not available
