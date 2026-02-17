package de.febis.crawler.parser

/**
 * Resolves Jimdo CDN image URLs to full resolution.
 *
 * Jimdo uses Cloudflare image resizing:
 *   https://image.jimcdn.com/cdn-cgi/image/width=2048,height=2048,fit=contain,format=jpg,/app/cms/storage/...
 * Full resolution (strip CDN transform):
 *   https://image.jimcdn.com/app/cms/storage/...
 */
object JimdoImageResolver {
    private val cdnPattern = Regex("(/cdn-cgi/image/[^/]*)")

    fun resolveFullResolution(url: String): String =
        url.replace(cdnPattern, "")
}
