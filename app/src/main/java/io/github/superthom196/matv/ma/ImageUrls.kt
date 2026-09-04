package io.github.superthom196.matv.ma

/**
 * Builds artwork URLs:
 *  - opaque form `{base}/imageproxy/{proxy_id}?size=N` when the server gives a proxy id
 *    (MA 2.10 only serves this form; the old `?path=&provider=` form returns 400),
 *  - remote http(s) images (fanart.tv, TheAudioDB, …) are loaded directly when there is no proxy id,
 *  - provider-local images without a proxy id cannot be fetched, so they get no URL.
 * Sizes are whitelisted by the server: 80, 160, 256, 512, 1024.
 */
object ImageUrls {
    private val sizes = intArrayOf(80, 160, 256, 512, 1024)

    fun normalize(size: Int): Int = sizes.firstOrNull { it >= size } ?: 1024

    fun forImage(base: String?, img: MediaItemImage?, size: Int): String? {
        if (base == null || img == null) return null
        val s = normalize(size)
        img.proxyId?.takeIf { it.isNotBlank() }?.let { return "$base/imageproxy/$it?size=$s" }
        val path = img.path ?: return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return null
    }

    fun forItem(base: String?, item: MediaItem?, size: Int): String? {
        if (item == null) return null
        forImage(base, item.thumb, size)?.let { return it }
        forImage(base, item.album?.image, size)?.let { return it }
        item.artists?.firstNotNullOfOrNull { forImage(base, it.image, size) }?.let { return it }
        return null
    }

    fun forQueueItem(base: String?, qi: QueueItem?, size: Int): String? {
        if (qi == null) return null
        forImage(base, qi.image, size)?.let { return it }
        return forItem(base, qi.mediaItem, size)
    }

    /** `player.current_media.image_url` is already a URL, but may be a bare imageproxy path. */
    fun forPlayerMedia(base: String?, url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http")) return url
        return base?.let { "$it${if (url.startsWith("/")) "" else "/"}$url" }
    }
}
