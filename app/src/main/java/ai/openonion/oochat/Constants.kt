package ai.openonion.oochat

/**
 * Application-wide constants.
 */
object Constants {
    const val DEFAULT_SERVER_URL = "https://oo.openonion.ai"

    val DEFAULT_SERVER_URLS = setOf(
        "https://oo.openonion.ai",
        "http://oo.openonion.ai",
        "wss://oo.openonion.ai",
        "ws://oo.openonion.ai"
    )

    /**
     * Normalises trailing slashes so a user-typed "https://oo.openonion.ai/"
     * still matches. The set lookup intentionally stays strict (no protocol
     * fallback) — we only want to recognise canonical, trusted relay endpoints.
     */
    fun isDefaultServerUrl(url: String?): Boolean {
        val normalised = url?.trimEnd('/')
        return normalised in DEFAULT_SERVER_URLS
    }
}
