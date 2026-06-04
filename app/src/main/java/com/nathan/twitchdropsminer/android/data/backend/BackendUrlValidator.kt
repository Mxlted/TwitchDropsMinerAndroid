package com.nathan.twitchdropsminer.android.data.backend

import java.net.URI

object BackendUrlValidator {
    fun normalize(input: String): Result<String> = runCatching {
        val trimmed = input.trim()
        require(trimmed.isNotBlank()) { "Backend URL is required." }

        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Backend URL must start with http:// or https://."
        }
        val host = uri.host?.lowercase()
        require(!host.isNullOrBlank()) { "Backend URL must include a host." }
        require(scheme != "http" || host.isLocalDebugHost()) {
            "HTTP backend URLs are limited to localhost, 127.0.0.1, ::1, or 10.0.2.2. Use HTTPS for other hosts."
        }
        require(uri.userInfo.isNullOrBlank()) { "Do not include credentials in the backend URL." }
        require(uri.rawQuery.isNullOrBlank() && uri.rawFragment.isNullOrBlank()) {
            "Backend URL should not include a query or fragment."
        }

        trimmed.trimEnd('/')
    }

    private fun String.isLocalDebugHost(): Boolean =
        this == "localhost" ||
            this == "127.0.0.1" ||
            this == "::1" ||
            this == "[::1]" ||
            this == "10.0.2.2"
}
