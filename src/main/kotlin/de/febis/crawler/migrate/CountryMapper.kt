package de.febis.crawler.migrate

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Maps country names from the old FEBIS site to ISO 3166-1 alpha-2 codes
 * used in febis-connect.
 */
object CountryMapper {

    /** All valid country codes with their canonical names (from febis-connect countries.ts + additional FEBIS countries) */
    private val countries = mapOf(
        // European Union
        "AT" to "Austria",
        "BE" to "Belgium",
        "BG" to "Bulgaria",
        "HR" to "Croatia",
        "CY" to "Cyprus",
        "CZ" to "Czech Republic",
        "DK" to "Denmark",
        "EE" to "Estonia",
        "FI" to "Finland",
        "FR" to "France",
        "DE" to "Germany",
        "GR" to "Greece",
        "HU" to "Hungary",
        "IE" to "Ireland",
        "IT" to "Italy",
        "LV" to "Latvia",
        "LT" to "Lithuania",
        "LU" to "Luxembourg",
        "MT" to "Malta",
        "NL" to "Netherlands",
        "PL" to "Poland",
        "PT" to "Portugal",
        "RO" to "Romania",
        "SK" to "Slovakia",
        "SI" to "Slovenia",
        "ES" to "Spain",
        "SE" to "Sweden",

        // Other European
        "AL" to "Albania",
        "AD" to "Andorra",
        "BY" to "Belarus",
        "BA" to "Bosnia and Herzegovina",
        "CH" to "Switzerland",
        "GB" to "United Kingdom",
        "IS" to "Iceland",
        "LI" to "Liechtenstein",
        "MC" to "Monaco",
        "MD" to "Moldova",
        "ME" to "Montenegro",
        "MK" to "North Macedonia",
        "NO" to "Norway",
        "RS" to "Serbia",
        "RU" to "Russia",
        "SM" to "San Marino",
        "TR" to "Turkey",
        "UA" to "Ukraine",
        "VA" to "Vatican City",

        // Americas
        "US" to "United States",
        "CA" to "Canada",
        "BR" to "Brazil",
        "CO" to "Colombia",
        "MX" to "Mexico",
        "PE" to "Peru",

        // Asia
        "JP" to "Japan",
        "SG" to "Singapore",
        "CN" to "China",
        "KR" to "South Korea",
        "HK" to "Hong Kong",
        "IN" to "India",
        "BD" to "Bangladesh",
        "ID" to "Indonesia",
        "JO" to "Jordan",
        "KZ" to "Kazakhstan",
        "MY" to "Malaysia",
        "TW" to "Taiwan",
        "TH" to "Thailand",

        // Middle East
        "AE" to "United Arab Emirates",
        "IL" to "Israel",

        // Africa
        "ZA" to "South Africa",
        "DZ" to "Algeria",
        "CI" to "Ivory Coast",
        "GH" to "Ghana",
        "KE" to "Kenya",
        "ML" to "Mali",
        "MU" to "Mauritius",

        // Oceania
        "AU" to "Australia",
        "NZ" to "New Zealand",
    )

    /** Reverse lookup: canonical name (lowercase) → code */
    private val nameToCode: Map<String, String> = countries.entries
        .associate { (code, name) -> name.lowercase() to code }

    /** Common alternative names / abbreviations found on the FEBIS site */
    private val aliases: Map<String, String> = mapOf(
        // Abbreviations
        "uk" to "GB",
        "usa" to "US",
        "uae" to "AE",
        "u.s.a." to "US",
        "u.s." to "US",

        // Alternative names
        "great britain" to "GB",
        "england" to "GB",
        "the netherlands" to "NL",
        "holland" to "NL",
        "united states of america" to "US",
        "czechia" to "CZ",
        "republic of ireland" to "IE",
        "bosnia" to "BA",
        "bosnia & herzegovina" to "BA",
        "macedonia" to "MK",
        "republic of north macedonia" to "MK",
        "türkiye" to "TR",
        "turkiye" to "TR",
        "republic of south africa" to "ZA",
        "korea" to "KR",
        "republic of korea" to "KR",
        "lichtenstein" to "LI",

        // Misspellings / variations from old FEBIS site
        "columbia" to "CO",
        "khazakhstan" to "KZ",
        "republic serbia" to "RS",
        "slovak republic" to "SK",
        "méxico" to "MX",
        "cote d'ivoire" to "CI",
        "côte d'ivoire" to "CI",
    )

    /**
     * Maps a country label to its ISO code.
     * Returns null if the country cannot be mapped.
     */
    fun toCode(label: String): String? {
        val normalized = label.trim().lowercase()

        // 1. Direct match on canonical name
        nameToCode[normalized]?.let { return it }

        // 2. Alias match
        aliases[normalized]?.let { return it }

        // 3. Already an ISO code?
        val upper = normalized.uppercase()
        if (upper.length == 2 && countries.containsKey(upper)) {
            return upper
        }

        return null
    }

    /**
     * Validates that ALL country labels can be mapped.
     * Returns a list of unmappable (label) entries.
     */
    fun findUnmappableCountries(labels: Set<String>): List<String> {
        return labels.filter { toCode(it) == null }
    }

    /**
     * Returns the canonical country name for a code.
     */
    fun toName(code: String): String? = countries[code.uppercase()]
}
