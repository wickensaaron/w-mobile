package com.nuvio.app.core.poster

import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import com.nuvio.app.core.network.WCoreNativeLibrary

/** Resolve opaque artwork references at request time; signed tickets never enter stored models. */
class WCoreImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val reference = chain.request.data as? String ?: return chain.proceed()
        if (!reference.startsWith("wcore-art://")) return chain.proceed()
        val owned = WCoreNativeLibrary.imageTicket(reference)
        val request = chain.request.newBuilder()
            .data(owned?.second ?: "wcore-unavailable://")
            .diskCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = chain.withRequest(request).proceed()
        if (owned != null && !WCoreNativeLibrary.isCurrent(owned.first)) {
            return chain.withRequest(request.newBuilder().data("wcore-unavailable://").build()).proceed()
        }
        return result
    }
}
