package io.github.superthom196.matv

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.bitmapFactoryMaxParallelism
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Bearer token shared with the artwork loader, so imageproxy requests are authenticated too.
 * [baseUrl] is the server it belongs to: the header goes to that host only. The server hands
 * out some artwork as plain URLs on other hosts (fanart.tv, TheAudioDB), and those must not
 * be shown a token that runs the hi-fi.
 */
object AuthHolder {
    @Volatile var token: String? = null
    @Volatile var baseUrl: String? = null

    /** Whether [url] is on the server the token belongs to: same host, same effective port. */
    fun isOurs(url: HttpUrl): Boolean {
        val home = baseUrl?.toHttpUrlOrNull() ?: return false
        return url.host == home.host && url.port == home.port
    }
}

class HiFiApp : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val tok = AuthHolder.token
                val req = chain.request()
                chain.proceed(if (tok != null && AuthHolder.isOurs(req.url)) req.newBuilder().header("Authorization", "Bearer $tok").build() else req)
            }
            .build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { http }))
                // Music Assistant ships its genre artwork as SVG; without this those tiles come back blank.
                add(coil3.svg.SvgDecoder.Factory())
            }
            // Two cores, so four decoder threads only fight the main thread during a scroll.
            .bitmapFactoryMaxParallelism(2)
            // Grid tiles snap in over a flat placeholder; hero artwork asks for its own fade per request.
            .crossfade(false)
            // A guard on a 192 MB heap, hardware bitmaps mostly live outside it.
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .build()
    }
}
