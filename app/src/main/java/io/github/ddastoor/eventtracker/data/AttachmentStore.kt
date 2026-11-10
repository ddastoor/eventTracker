package io.github.ddastoor.eventtracker.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Files attached to events. New files are copied into [pendingDir] until they are uploaded to
 * Drive; downloaded files are kept in [cacheDir] so the viewer opens them instantly next time.
 */
class AttachmentStore(
    private val pendingDir: File,
    private val cacheDir: File,
    private val api: GoogleApi,
) {
    private val downloadLock = Mutex()

    fun pendingFile(localKey: String) = File(pendingDir, localKey)

    private fun cachedFile(fileId: String) = File(cacheDir, fileId)

    /** Copies a picked document into app storage and returns a not-yet-uploaded attachment. */
    suspend fun import(resolver: ContentResolver, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val key = UUID.randomUUID().toString()
        val dest = pendingFile(key).also { it.parentFile?.mkdirs() }
        val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the selected file")
        input.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        val mime = resolver.getType(uri)
            ?: name?.substringAfterLast('.', "")?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
            ?: "application/octet-stream"
        Attachment(Attachment.LOCAL_PREFIX + key, name ?: "file-$key", mime)
    }

    /** A fresh file for the camera app to write a photo into. */
    fun newPhotoTarget(): Pair<Attachment, File> {
        val key = UUID.randomUUID().toString()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = pendingFile(key).also { it.parentFile?.mkdirs() }
        return Attachment(Attachment.LOCAL_PREFIX + key, "photo_$stamp.jpg", "image/jpeg") to file
    }

    /** After upload, keep the local copy as the cached copy of the Drive file. */
    fun adopt(localKey: String, driveFileId: String) {
        val src = pendingFile(localKey)
        if (!src.exists()) return
        cacheDir.mkdirs()
        if (!src.renameTo(cachedFile(driveFileId))) src.delete()
    }

    fun discard(localKey: String) {
        pendingFile(localKey).delete()
    }

    /** Returns a local file with the attachment's content, downloading it if needed. */
    suspend fun open(attachment: Attachment): File {
        if (attachment.isLocal) {
            val f = pendingFile(attachment.localKey)
            if (f.exists()) return f
            throw IOException("This file is no longer on the device")
        }
        val cached = cachedFile(attachment.fileId)
        if (cached.exists()) return cached
        return downloadLock.withLock {
            if (!cached.exists()) api.download(attachment.fileId, cached)
            cached
        }
    }

    fun clearAll() {
        pendingDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }
}
