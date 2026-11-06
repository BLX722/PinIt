package com.hawatri.pinit.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingShareTest {

    @Test
    fun screenshotMimeBecomesImage() {
        assertEquals(
            IncomingShareKind.IMAGE,
            classifyIncomingShare("image/png", null, hasStream = true)
        )
        assertEquals(
            IncomingShareKind.IMAGE,
            classifyIncomingShare("image/jpeg", "caption", hasStream = true)
        )
    }

    @Test
    fun imageMimeWithoutBytesIsIgnored() {
        assertEquals(
            IncomingShareKind.UNSUPPORTED,
            classifyIncomingShare("image/png", null, hasStream = false)
        )
    }

    @Test
    fun plainUrlBecomesLink() {
        assertEquals(
            IncomingShareKind.LINK,
            classifyIncomingShare("text/plain", "  https://example.com/a  ", hasStream = false)
        )
    }

    @Test
    fun sentenceContainingUrlStaysText() {
        assertEquals(
            IncomingShareKind.TEXT,
            classifyIncomingShare("text/plain", "see https://example.com", hasStream = false)
        )
    }

    @Test
    fun pdfAndAudioFollowMime() {
        assertEquals(
            IncomingShareKind.PDF,
            classifyIncomingShare("application/pdf", null, hasStream = true)
        )
        assertEquals(
            IncomingShareKind.AUDIO,
            classifyIncomingShare("audio/mpeg", null, hasStream = true)
        )
    }

    @Test
    fun calendarShareIsNotAutoPinned() {
        assertEquals(
            IncomingShareKind.UNSUPPORTED,
            classifyIncomingShare("text/calendar", null, hasStream = true)
        )
    }

    @Test
    fun blankTextIsIgnored() {
        assertEquals(
            IncomingShareKind.UNSUPPORTED,
            classifyIncomingShare("text/plain", "   ", hasStream = false)
        )
    }

    @Test
    fun urlCheckRejectsWhitespace() {
        assertTrue(isHttpUrl("http://example.com"))
        assertFalse(isHttpUrl("http://example.com/a b"))
        assertFalse(isHttpUrl("example.com"))
    }
}
