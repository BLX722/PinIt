package com.hawatri.pinit.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.google.gson.Gson
import com.hawatri.pinit.R
import com.hawatri.pinit.data.AppPreferences
import com.hawatri.pinit.data.Note
import com.hawatri.pinit.data.NoteDatabase
import com.hawatri.pinit.data.NoteType
import com.hawatri.pinit.ui.AudioNoteData
import com.hawatri.pinit.ui.LinkNoteData
import com.hawatri.pinit.ui.fetchLinkMetadata
import com.hawatri.pinit.util.LocaleHelper
import com.hawatri.pinit.util.NotificationHelper
import com.hawatri.pinit.widget.AddWidgets
import com.hawatri.pinit.widget.PinItWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Share-sheet target that never shows PinIt. It copies the payload into app
 * storage, saves a pinned note, posts the notification, and finishes so the
 * user stays in the app they shared from.
 *
 * The activity has to exist — ACTION_SEND grants are delivered to an activity,
 * and they die when that activity finishes — so the copy happens before finish().
 */
class SharePinActivity : Activity() {

    private val started = AtomicBoolean(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressTransitions(open = true)
        val kind = classifyIncomingShare(
            intent.type,
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.sharedStream() != null
        )
        if (kind == IncomingShareKind.UNSUPPORTED) {
            finishQuietly()
            return
        }
        // No permission dialog here. A translucent activity that waits on a system
        // dialog can leave the user stuck on an invisible task. Onboarding already
        // asks for notifications; if they're off we still save the note and say so.
        startWorker()
    }

    private fun startWorker() {
        if (!started.compareAndSet(false, true)) return
        val launch = intent
        thread(name = "share-pin") {
            val outcome = try {
                pinFromIntent(launch)
            } catch (_: Exception) {
                PinOutcome.FAILED
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (outcome) {
                    PinOutcome.PINNED -> toast(R.string.msg_share_pinned)
                    PinOutcome.NEEDS_PERMISSION -> toast(R.string.msg_notification_permission_required)
                    PinOutcome.FAILED -> toast(R.string.msg_share_pin_failed)
                    PinOutcome.IGNORED -> Unit
                }
                finishQuietly()
            }
        }
    }

    private fun pinFromIntent(launch: Intent): PinOutcome {
        if (launch.action != Intent.ACTION_SEND) return PinOutcome.IGNORED
        val stream = launch.sharedStream()
        val text = launch.getStringExtra(Intent.EXTRA_TEXT)
        val subject = launch.getStringExtra(Intent.EXTRA_SUBJECT)
        val mime = launch.type
        val kind = classifyIncomingShare(mime, text, stream != null)
        val app = applicationContext
        val copied = mutableListOf<File>()
        val note = try {
            when (kind) {
                IncomingShareKind.IMAGE -> fileNote(app, stream, mime, NoteType.IMAGE, "jpg", R.string.type_image, copied)
                IncomingShareKind.PDF -> fileNote(app, stream, mime, NoteType.PDF, "pdf", R.string.pdf_document, copied)
                IncomingShareKind.AUDIO -> audioNote(app, stream, mime, copied)
                IncomingShareKind.LINK -> linkNote(text.orEmpty())
                IncomingShareKind.TEXT -> textNote(text.orEmpty(), subject)
                IncomingShareKind.UNSUPPORTED -> return PinOutcome.IGNORED
            }
        } catch (_: Exception) {
            copied.forEach { it.delete() }
            return PinOutcome.FAILED
        }
        if (note == null) {
            copied.forEach { it.delete() }
            return PinOutcome.FAILED
        }
        runBlocking(Dispatchers.IO) {
            NoteDatabase.getDatabase(app).noteDao().insertNote(note)
        }
        AppPreferences.setLastModifiedNow(app)
        NotificationHelper(app).pinNoteToNotification(
            note.id, note.title, note.text, note.isList, note.noteType
        )
        refreshWidgets(app)
        if (kind == IncomingShareKind.LINK) enrichLink(app, note.id, text.orEmpty().trim())
        return if (NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            PinOutcome.PINNED
        } else {
            PinOutcome.NEEDS_PERMISSION
        }
    }

    private fun fileNote(
        app: Context,
        stream: Uri?,
        mime: String?,
        noteType: String,
        fallbackExt: String,
        fallbackTitle: Int,
        copied: MutableList<File>
    ): Note? {
        val uri = stream ?: return null
        val name = displayName(app, uri)
        val ext = extensionFor(name, mime, fallbackExt)
        val file = copyTo(app, uri, "shares", ext)
        copied += file
        val stored = FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
        return Note(
            title = titleFromFileName(name, app.getString(fallbackTitle)),
            text = stored.toString(),
            formatRanges = emptyList(),
            isPinned = true,
            noteType = noteType
        )
    }

    private fun audioNote(
        app: Context,
        stream: Uri?,
        mime: String?,
        copied: MutableList<File>
    ): Note? {
        val uri = stream ?: return null
        val name = displayName(app, uri)
        val ext = extensionFor(name, mime, "m4a")
        val file = copyTo(app, uri, "recordings", ext)
        copied += file
        val duration = durationMs(file.absolutePath)
        val data = AudioNoteData(file.absolutePath, duration)
        val fallback = app.getString(R.string.type_audio)
        return Note(
            title = titleFromFileName(name, fallback),
            text = Gson().toJson(data),
            formatRanges = emptyList(),
            isPinned = true,
            noteType = NoteType.AUDIO
        )
    }

    private fun linkNote(raw: String): Note {
        val url = raw.trim()
        val data = LinkNoteData(url, url, "", "", false)
        return Note(
            title = url,
            text = Gson().toJson(data),
            formatRanges = emptyList(),
            isPinned = true,
            noteType = NoteType.LINK
        )
    }

    private fun textNote(raw: String, subject: String?): Note {
        val body = raw.trim()
        val fromSubject = subject?.trim()?.take(TITLE_LIMIT).orEmpty()
        val title = fromSubject.ifBlank {
            body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_LIMIT).orEmpty()
        }
        return Note(
            title = title,
            text = body,
            formatRanges = emptyList(),
            isPinned = true,
            noteType = NoteType.TEXT
        )
    }

    private fun copyTo(context: Context, uri: Uri, dirName: String, ext: String): File {
        val dir = File(context.filesDir, dirName).apply { mkdirs() }
        val dest = File(dir, "${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            copyCapped(input, dest)
        } ?: throw IOException("unreadable")
        if (!dest.exists() || dest.length() <= 0L) {
            dest.delete()
            throw IOException("empty")
        }
        return dest
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, getString(resId), Toast.LENGTH_SHORT).show()
    }

    private fun finishQuietly() {
        suppressTransitions(open = false)
        finish()
    }

    private fun suppressTransitions(open: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val kind = if (open) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE
            overrideActivityTransition(kind, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TITLE_LIMIT = 80
        private const val MAX_SHARE_BYTES = 80L * 1024 * 1024

        private val enrichScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun enrichLink(app: Context, noteId: String, rawUrl: String) {
            enrichScope.launch {
                val meta = try {
                    fetchLinkMetadata(rawUrl)
                } catch (_: Exception) {
                    null
                } ?: return@launch
                val dao = NoteDatabase.getDatabase(app).noteDao()
                val existing = dao.getNoteById(noteId) ?: return@launch
                val url = rawUrl.trim()
                // Skip if the user already unpinned or renamed the note.
                if (!existing.isPinned || existing.noteType != NoteType.LINK || existing.title != url) return@launch
                val data = LinkNoteData(
                    url = url,
                    title = meta.title.ifBlank { url },
                    description = meta.description,
                    imageUrl = meta.imageUrl,
                    isVideo = meta.isVideo
                )
                val updated = existing.copy(title = data.title, text = Gson().toJson(data))
                dao.updateNote(updated)
                AppPreferences.setLastModifiedNow(app)
                NotificationHelper(app).pinNoteToNotification(
                    updated.id, updated.title, updated.text, false, NoteType.LINK
                )
                refreshWidgets(app)
            }
        }

        private fun refreshWidgets(app: Context) {
            runCatching { PinItWidget.requestUpdate(app) }
            runCatching { AddWidgets.requestUpdateAll(app) }
        }

        private fun copyCapped(input: InputStream, dest: File) {
            try {
                dest.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SHARE_BYTES) throw IOException("too large")
                        output.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
        }

        private fun displayName(context: Context, uri: Uri): String? {
            if (uri.scheme == "content") {
                try {
                    context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) return cursor.getString(index)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return uri.lastPathSegment?.substringAfterLast('/')
        }

        private fun extensionFor(name: String?, mime: String?, fallback: String): String {
            val fromName = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (fromName.length in 1..8 && fromName.all { it.isLetterOrDigit() } && name?.contains('.') == true) {
                return fromName
            }
            val cleanMime = mime?.substringBefore(';')?.trim()
            val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(cleanMime)
            val ext = fromMime?.lowercase()?.takeIf { it.isNotBlank() } ?: fallback
            return ext.filter { it.isLetterOrDigit() }.ifBlank { fallback }
        }

        private fun titleFromFileName(name: String?, fallback: String): String {
            val base = name?.substringBeforeLast('.')?.trim().orEmpty()
            return base.ifBlank { fallback }.take(TITLE_LIMIT)
        }

        private fun durationMs(path: String): Long {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
                0L
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }

        private fun Intent.sharedStream(): Uri? {
            val extra = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
            if (extra != null) return extra
            val clip = clipData ?: return null
            if (clip.itemCount == 0) return null
            return clip.getItemAt(0).uri
        }
    }
}

private enum class PinOutcome {
    PINNED,
    NEEDS_PERMISSION,
    FAILED,
    IGNORED
}
