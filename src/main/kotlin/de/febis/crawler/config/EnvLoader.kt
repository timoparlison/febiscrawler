package de.febis.crawler.config

import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Loads variables from a .env file into a map.
 * Does NOT override existing system environment variables.
 */
object EnvLoader {

    private var loaded: Map<String, String> = emptyMap()

    fun load(path: Path = Path.of(".env")): Map<String, String> {
        if (!Files.exists(path)) {
            logger.warn { "No .env file found at $path" }
            return emptyMap()
        }

        loaded = Files.readAllLines(path)
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().removeSurrounding("\"")
                key to value
            }
            .toMap()

        logger.info { "Loaded ${loaded.size} variables from $path" }
        return loaded
    }

    /**
     * Gets a value: system env takes precedence, then .env file.
     */
    fun get(key: String): String? =
        System.getenv(key) ?: loaded[key]

    fun require(key: String): String =
        get(key) ?: error("Required environment variable '$key' not set. Check your .env file.")
}
