# Business Dokumentation: FEBIS Event-Migration

## Projektübersicht

Migration aller Event-Daten von der bestehenden FEBIS Jimdo-Website zu einer neuen Plattform. FEBIS (Federation of European Business Information Services) organisiert jährliche General Assemblies und weitere Veranstaltungen, deren historische Daten erhalten bleiben sollen.

## Quellsystem

- **Plattform:** Jimdo (Server-Rendered HTML, kein SPA)
- **Zugang:** Members-Bereich mit Passwort-Schutz (Passwort: "torino", kein Username)
- **Struktur:** Hauptmenü "General Assembly" mit Unterseiten pro Event (ca. 70 Events, 2009-2025)

## Datenumfang pro Event

### Event-Metadaten
- Titel (z.B. "2024 Nice", "2023 Hamburg (50th anniversary)")
- Datum/Zeitraum (z.B. "25.09.2024 - 27.09.2024")
- Ort

### Hotel-Informationen
- Hotelname
- Adresse
- Website-Link
- Hotelbilder (Slideshow)

### Dokumente (PDFs)
- Convocation / Einladung
- Agenda
- Teilnehmerliste
- Meeting Program
- Präsentationen (mehrere pro Event, mit Sprecher und Titel)
- Reports (Treasurer, Auditors)
- Satisfaction Survey
- Sponsoring-Optionen
- Compliance Guidelines

### Videos
- YouTube-Embeds (typisch: Extended Version + Short/Highlights)
- Titel pro Video

### Foto-Galerien
- Gruppiert nach Anlass/Datum (z.B. "September 25", "Gala dinner - Le Negresco")
- Ca. 200 Bilder pro Event
- Bilder als Thumbnails mit Link zur Vollauflösung

## Datenmodell

```kotlin
data class Event(
    val id: String,                 // Slug, z.B. "2024-nice"
    val title: String,              // Originaltitel von der Seite
    val dateRange: String?,         // Falls vorhanden
    val location: String?,          // Stadt/Ort
    val hotelInfo: HotelInfo?,
    val documents: List<Document>,
    val videos: List<Video>,
    val galleries: List<Gallery>
)

data class HotelInfo(
    val name: String,
    val address: String,
    val websiteUrl: String?,
    val images: List<String>        // Lokale Pfade nach Download
)

data class Document(
    val title: String,              // Angezeigter Titel
    val filename: String,           // Originaler Dateiname
    val category: DocumentCategory, // Klassifizierung
    val originalUrl: String,
    val localPath: String,
    val sizeDescription: String     // "418.1 KB"
)

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

data class Video(
    val title: String,
    val youtubeUrl: String,         // Volle URL
    val youtubeId: String           // Nur die Video-ID
)

data class Gallery(
    val title: String,              // Abschnittstitel
    val sortOrder: Int,             // Reihenfolge auf der Seite
    val images: List<GalleryImage>
)

data class GalleryImage(
    val originalUrl: String,
    val localPath: String,
    val sortOrder: Int              // Reihenfolge im Album
)
```

## Output-Struktur

```
/output
  /events
    /2024-nice
      meta.json                     # Vollständiges Event-Objekt
      /documents
        convocation-ga-2024.pdf
        agenda-2024.pdf
        presentation-armin-kammel-supply-chain.pdf
        ...
      /hotel-images
        hotel-001.jpg
        ...
      /galleries
        /gala-dinner-le-negresco
          001.jpg
          002.jpg
          ...
        /september-25
          001.jpg
          ...
    /2023-hamburg
      ...
  events-index.json                 # Übersicht aller Events mit Basis-Metadaten
  migration-log.json                # Protokoll: Erfolge, Fehler, Übersprungene
```

## Geschäftsregeln

1. **Vollständigkeit:** Alle verfügbaren Daten migrieren, nichts auslassen
2. **Dateinamen:** Slugify (Kleinbuchstaben, Bindestriche, keine Sonderzeichen)
3. **Bilder:** Immer höchste verfügbare Auflösung downloaden
4. **Duplikate:** Gleiche Datei nur einmal speichern, per Hash erkennen
5. **Reihenfolge:** Originale Sortierung der Galerien und Dokumente beibehalten
6. **Fehlertoleranz:** Einzelne fehlgeschlagene Downloads nicht den Gesamtprozess abbrechen

## Bekannte Besonderheiten

- Manche ältere Events haben weniger Inhalte (keine Videos, weniger Bilder)
- "Virtual" Events (2020-2022) haben keine Galerien/Hotel-Infos
- Jimdo speichert Bilder in verschiedenen Auflösungen – URL-Pattern beachten
- PDF-Titel enthalten oft das Datum als Präfix (z.B. "20240926 Prof. Dr...")
