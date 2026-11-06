package com.hawatri.pinit.share

/**
 * What an ACTION_SEND into PinIt should become. Pure so it can be tested
 * without an Android runtime. Calendar (.ics) shares are intentionally left
 * unsupported here — those still open the import sheet.
 */
enum class IncomingShareKind {
    TEXT,
    LINK,
    IMAGE,
    PDF,
    AUDIO,
    UNSUPPORTED
}

fun classifyIncomingShare(mimeType: String?, text: String?, hasStream: Boolean): IncomingShareKind {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val body = text?.trim().orEmpty()
    return when {
        mime.startsWith("image/") && hasStream -> IncomingShareKind.IMAGE
        mime == "application/pdf" && hasStream -> IncomingShareKind.PDF
        mime.startsWith("audio/") && hasStream -> IncomingShareKind.AUDIO
        isShareableText(mime) && isHttpUrl(body) -> IncomingShareKind.LINK
        isShareableText(mime) && body.isNotEmpty() -> IncomingShareKind.TEXT
        else -> IncomingShareKind.UNSUPPORTED
    }
}

fun isHttpUrl(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.any { it.isWhitespace() }) return false
    return trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
}

private fun isShareableText(mime: String): Boolean =
    mime.isEmpty() || mime == "text/plain" || mime == "text/*"
