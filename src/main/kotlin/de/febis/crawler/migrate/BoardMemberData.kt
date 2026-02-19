package de.febis.crawler.migrate

data class BoardMemberData(
    val name: String,
    val role: String?,
    val company: String? = null,
    val location: String? = null,
    val currentPositions: String? = null,
    val profile: String? = null,
    val ambition: String? = null,
    val linkedinUrl: String? = null,
    val imageUrl: String? = null,
    val sortOrder: Int = 0
)
