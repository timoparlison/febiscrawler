package de.febis.crawler.migrate

data class TeamMemberData(
    val name: String,
    val role: String?,
    val phone: String? = null,
    val mobile: String? = null,
    val email: String? = null,
    val linkedinUrl: String? = null,
    val imageUrl: String? = null,
    val sortOrder: Int = 0
)
