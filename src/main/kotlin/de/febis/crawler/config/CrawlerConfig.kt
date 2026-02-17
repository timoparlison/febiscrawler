package de.febis.crawler.config

import java.nio.file.Path

data class CrawlerConfig(
    val baseUrl: String,
    val loginPath: String = "/members-login",
    val password: String = "torino",
    val indexPath: String,
    val outputDir: Path = Path.of("./crawledData"),
    val maxParallelDownloads: Int = 5,
    val requestDelayMs: Long = 100,
    val maxRetries: Int = 3
)
