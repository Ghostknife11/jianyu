package org.jianyu.core.domain

import java.net.URI
import java.util.Locale

/** A displayable HTTPS destination, never a claim that the destination or its content is trusted. */
fun externalSourceHost(url: String?): String? {
    if (url.isNullOrBlank() || url != url.trim()) return null
    return runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.rawFragment != null || uri.port !in -1..65535
        ) null else uri.host.lowercase(Locale.ROOT)
    }.getOrNull()
}
