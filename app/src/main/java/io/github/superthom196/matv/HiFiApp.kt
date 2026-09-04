package io.github.superthom196.matv

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Bearer token shared with the artwork loader, so imageproxy requests are authenticated too. */
object AuthHolder {
    @Volatile var token: String? = null
}

class HiFiApp : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val tok = AuthHolder.token
                val req = if (tok != null) chain.request().newBuilder().header("Authorization", "Bearer $tok").build() else chain.request()
                chain.proceed(req)
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .crossfade(true)
            .build()
    }
}
