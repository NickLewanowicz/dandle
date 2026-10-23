package app.dandle.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI

/**
 * Rewrites each request's scheme/host/port to the user's server URL, keeping the path+query
 * that Retrofit resolved against a placeholder base. This lets one Retrofit instance serve any
 * self-hosted server. Pure JVM.
 */
class BaseUrlInterceptor(private val serverUrlProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val base = serverUrlProvider()?.let { raw ->
            runCatching { URI(raw.trim()) }.getOrNull()
        }
        val request = chain.request()
        if (base == null || base.host.isNullOrBlank()) {
            return chain.proceed(request)
        }
        val url = request.url.newBuilder()
            .scheme(base.scheme?.lowercase() ?: "https")
            .host(base.host!!.lowercase())
            .port(base.port.takeIf { it != -1 } ?: if (base.scheme == "http") 80 else 443)
            .build()
        return chain.proceed(request.newBuilder().url(url).build())
    }
}
