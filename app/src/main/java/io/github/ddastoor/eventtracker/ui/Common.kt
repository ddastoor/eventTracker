package io.github.ddastoor.eventtracker.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ddastoor.eventtracker.data.EventType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

val EventType.composeColor: Color get() = Color(color)

/** Readable text color on top of [background]. */
fun contentColorOn(background: Color): Color = if (background.luminance() > 0.45f) Color(0xFF1B1B1F) else Color.White

/** Round badge with the type's emoji, or its first letter, on the type's color. */
@Composable
fun TypeAvatar(type: EventType?, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val bg = type?.composeColor ?: MaterialTheme.colorScheme.outline
    Box(
        modifier.size(size).background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val label = type?.emoji?.ifBlank { null } ?: type?.name?.take(1)?.uppercase() ?: "?"
        Text(
            label,
            color = contentColorOn(bg),
            fontSize = (size.value * 0.45f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        action?.invoke()
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickDialog(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(hour: Int, minute: Int, onPicked: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val state = rememberTimePickerState(hour, minute, android.text.format.DateFormat.is24HourFormat(ctx))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPicked(state.hour, state.minute); onDismiss() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state) },
    )
}

object Fmt {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val date = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    private val shortDate = DateTimeFormatter.ofPattern("MMM d, yyyy")
    private val month = DateTimeFormatter.ofPattern("MMMM yyyy")
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun dateTime(epochSeconds: Long): String {
        val t = local(epochSeconds)
        return "${date.format(t)} · ${time.format(t)}"
    }

    fun date(epochSeconds: Long): String = date.format(local(epochSeconds))
    fun date(d: LocalDate): String = shortDate.format(d)
    fun time(epochSeconds: Long): String = time.format(local(epochSeconds))
    fun month(epochSeconds: Long): String = month.format(local(epochSeconds))

    fun local(epochSeconds: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), zone)

    fun toEpoch(t: LocalDateTime): Long = t.atZone(zone).toEpochSecond()

    /** "today", "yesterday", "5 days ago", "in 3 days", "2 months ago", ... (by calendar day). */
    fun relative(epochSeconds: Long, today: LocalDate = LocalDate.now(zone)): String {
        val d = local(epochSeconds).toLocalDate()
        val days = ChronoUnit.DAYS.between(d, today)
        val future = days < 0
        val n = kotlin.math.abs(days)
        val text = when {
            n == 0L -> return "today"
            n == 1L -> return if (future) "tomorrow" else "yesterday"
            n < 45 -> "$n days"
            n < 365 * 2 -> "${(n / 30.4).toInt()} months"
            else -> "${"%.1f".format(n / 365.25)} years"
        }
        return if (future) "in $text" else "$text ago"
    }

    fun gap(days: Double): String = when {
        days < 1.5 -> "%.1f days".format(days)
        days < 60 -> "${days.toInt()} days"
        days < 730 -> "%.1f months".format(days / 30.44)
        else -> "%.1f years".format(days / 365.25)
    }
}

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}
