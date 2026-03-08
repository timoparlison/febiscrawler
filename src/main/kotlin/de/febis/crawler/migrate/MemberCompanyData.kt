package de.febis.crawler.migrate

data class MemberCompanyData(
    val name: String,
    val country: String,
    val detailUrl: String? = null,
    val b2bInformation: Boolean = false,
    val b2cInformation: Boolean = false,
    val compliance: Boolean = false,
    val debtCollection: Boolean = false,
    val marketingServices: Boolean = false,
    val creditManagementSoftware: Boolean = false,
    val otherServices: Boolean = false,
    val sortOrder: Int = 0
)
