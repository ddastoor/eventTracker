package io.github.ddastoor.eventtracker.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.ddastoor.eventtracker.data.Attachment
import io.github.ddastoor.eventtracker.data.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Loaded {
    data object Loading : Loaded
    data class Ready(val file: File) : Loaded
    data class Failed(val message: String) : Loaded
}

/** Read-only preview of an attachment: images (pinch to zoom), PDFs, text; others open externally. */
@Composable
fun AttachmentViewer(attachment: Attachment, store: AttachmentStore, modifier: Modifier = Modifier) {
    var state by remember(attachment.fileId) { mutableStateOf<Loaded>(Loaded.Loading) }
    var attempt by remember(attachment.fileId) { mutableIntStateOf(0) }
    LaunchedEffect(attachment.fileId, attempt) {
        state = Loaded.Loading
        state = try {
            Loaded.Ready(store.open(attachment))
        } catch (e: Exception) {
            Loaded.Failed(e.message ?: "Could not load the file")
        }
    }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            Loaded.Loading -> CircularProgressIndicator()
            is Loaded.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                OutlinedButton(onClick = { attempt++ }) { Text("Try again") }
            }
            is Loaded.Ready -> when {
                attachment.isImage -> ImagePreview(s.file)
                attachment.isPdf -> PdfPreview(s.file)
                attachment.mimeType.startsWith("text/") || attachment.mimeType == "application/json" -> TextPreview(s.file)
                else -> OtherFile(attachment, s.file)
            }
        }
    }
}

@Composable
private fun ImagePreview(file: File) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPx = with(density) { maxOf(maxWidth, maxHeight).toPx() }.toInt().coerceIn(512, 2560)
        var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(file) { mutableStateOf(false) }
        LaunchedEffect(file, maxPx) {
            val b = withContext(Dispatchers.IO) { runCatching { decodeSampled(file, maxPx * 2) }.getOrNull() }
            if (b == null) failed = true else bitmap = b.asImageBitmap()
        }
        val img = bitmap
        when {
            img != null -> ZoomableImage(img)
            failed -> Text("This image can't be shown.", Modifier.align(Alignment.Center))
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ZoomableImage(img: ImageBitmap) {
    var scale by remember(img) { mutableFloatStateOf(1f) }
    var offset by remember(img) { mutableStateOf(Offset.Zero) }
    Image(
        img,
        contentDescription = "Attached image",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(img) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(img) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

/** Decodes an image no larger than [maxDim] px, honoring EXIF rotation where the OS supports it. */
private fun decodeSampled(file: File, maxDim: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val largest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (largest / (sample * 2) >= maxDim) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        // Fall through to BitmapFactory for formats ImageDecoder rejects.
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun PdfPreview(file: File) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    val lock = remember(file) { Mutex() }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) {
        Text("This PDF can't be shown.")
        return
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceIn(300, 2000) * 2
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(renderer.pageCount) { index ->
                var page by remember(index) { mutableStateOf<ImageBitmap?>(null) }
                var ratio by remember(index) { mutableFloatStateOf(1 / 1.414f) }
                LaunchedEffect(index, widthPx) {
                    page = withContext(Dispatchers.IO) {
                        lock.withLock {
                            runCatching {
                                renderer.openPage(index).use { p ->
                                    ratio = p.width.toFloat() / p.height
                                    val w = widthPx
                                    val h = (w / ratio).toInt().coerceAtLeast(1)
                                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    bmp.asImageBitmap()
                                }
                            }.getOrNull()
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.White), contentAlignment = Alignment.Center) {
                    page?.let { Image(it, "Page ${index + 1}", Modifier.fillMaxSize()) } ?: CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun TextPreview(file: File) {
    var text by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                file.inputStream().use { input ->
                    val bytes = ByteArray(MAX_TEXT)
                    val n = input.read(bytes).coerceAtLeast(0)
                    String(bytes, 0, n) + if (file.length() > MAX_TEXT) "\n…" else ""
                }
            }.getOrDefault("Can't read this file.")
        }
    }
    val t = text ?: return CircularProgressIndicator()
    SelectionContainer(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
    ) { Text(t, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun OtherFile(attachment: Attachment, file: File) {
    val ctx = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(48.dp))
        Text(attachment.name, textAlign = TextAlign.Center)
        Text("No preview for ${attachment.mimeType}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { openExternally(ctx, file, attachment) }) { Text("Open with another app") }
    }
}

/** Opens [file] read-only in another app (e.g. a full PDF viewer). */
fun openExternally(ctx: Context, file: File, attachment: Attachment) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(intent, attachment.name))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private const val MAX_TEXT = 256 * 1024
