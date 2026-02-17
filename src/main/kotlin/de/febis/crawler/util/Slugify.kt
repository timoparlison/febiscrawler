package de.febis.crawler.util

import java.text.Normalizer

object Slugify {
    fun slugify(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
