package io.github.ddastoor.eventtracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.shapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import io.github.ddastoor.eventtracker.data.Event
import io.github.ddastoor.eventtracker.data.Snapshot
import io.github.ddastoor.eventtracker.stats.FrequencyData
import io.github.ddastoor.eventtracker.stats.Period
import io.github.ddastoor.eventtracker.stats.Stats
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class PeriodOption(val label: String) {
    DAY("Day"), WEEK("Week"), MONTH("Month"), HALF_YEAR("6 months"), YEAR("Year"), DAYS("Days…"), MONTHS("Months…")
}

private enum class RangeOption(val label: String) {
    D30("30 days"), M3("3 months"), M6("6 months"), Y1("1 year"), Y2("2 years"), Y5("5 years"), ALL("All time"), CUSTOM("Custom…")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartsScreen(snapshot: Snapshot, onOpenEvent: (String) -> Unit) {
    if (snapshot.types.isEmpty()) {
        EmptyState("Nothing to chart yet", "Create an event type and log some events to see how often they happen.")
        return
    }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }

    val defaultType = snapshot.types.firstOrNull { it.isPreset }?.id ?: snapshot.types.first().id
    var selectedTypes by rememberSaveable { mutableStateOf(listOf(defaultType)) }
    var periodOption by rememberSaveable { mutableStateOf(PeriodOption.MONTH) }
    var customN by rememberSaveable { mutableStateOf(10) }
    var rangeOption by rememberSaveable { mutableStateOf(RangeOption.Y1) }
    var customFrom by rememberSaveable { mutableStateOf(today.minusMonths(6).toEpochDay()) }
    var customTo by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var askCustomPeriod by remember { mutableStateOf<PeriodOption?>(null) }
    var askCustomRange by remember { mutableStateOf(false) }
    var selectedBucket by remember { mutableStateOf<Int?>(null) }

    // Drop selections of types that were deleted.
    LaunchedEffect(snapshot.types) {
        val valid = selectedTypes.filter { id -> snapshot.types.any { it.id == id } }
        if (valid != selectedTypes) selectedTypes = valid.ifEmpty { listOf(defaultType) }
    }

    val period: Period = when (periodOption) {
        PeriodOption.DAY -> Period.DAY
        PeriodOption.WEEK -> Period.Week
        PeriodOption.MONTH -> Period.MONTH
        PeriodOption.HALF_YEAR -> Period.HALF_YEAR
        PeriodOption.YEAR -> Period.YEAR
        PeriodOption.DAYS -> Period.Days(customN)
        PeriodOption.MONTHS -> Period.Months(customN)
    }
    val selectedEvents = remember(snapshot.events, selectedTypes) {
        snapshot.events.filter { it.typeId in selectedTypes }
    }
    val (from, to) = remember(rangeOption, selectedEvents, customFrom, customTo) {
        val first = selectedEvents.minOfOrNull { Stats.toDate(it.timestamp, zone) }
        val last = selectedEvents.maxOfOrNull { Stats.toDate(it.timestamp, zone) }
        when (rangeOption) {
            RangeOption.D30 -> today.minusDays(29) to today
            RangeOption.M3 -> today.minusMonths(3).plusDays(1) to today
            RangeOption.M6 -> today.minusMonths(6).plusDays(1) to today
            RangeOption.Y1 -> today.minusYears(1).plusDays(1) to today
            RangeOption.Y2 -> today.minusYears(2).plusDays(1) to today
            RangeOption.Y5 -> today.minusYears(5).plusDays(1) to today
            RangeOption.ALL -> (first ?: today) to maxOf(today, last ?: today)
            RangeOption.CUSTOM -> LocalDate.ofEpochDay(customFrom) to LocalDate.ofEpochDay(customTo)
        }
    }
    val result = remember(selectedEvents, selectedTypes, from, to, period) {
        runCatching {
            val byType = selectedTypes.associateWith { id -> selectedEvents.filter { it.typeId == id }.map { it.timestamp } }
            val data = Stats.frequency(byType, from, to, period, zone)
            data to Stats.summary(selectedEvents.map { it.timestamp }, data, zone)
        }
    }
    LaunchedEffect(result) { selectedBucket = null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionLabel("Event types")
        FlowRow(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            snapshot.types.forEach { t ->
                val on = t.id in selectedTypes
                FilterChip(
                    selected = on,
                    onClick = {
                        selectedTypes = if (on) (selectedTypes - t.id).ifEmpty { selectedTypes } else selectedTypes + t.id
                    },
                    label = { Text(t.name) },
                    leadingIcon = { TypeAvatar(t, 18.dp) },
                )
            }
        }
        SectionLabel("Count per")
        ChipRow(PeriodOption.entries, periodOption, label = { o ->
            when (o) {
                PeriodOption.DAYS -> if (periodOption == o) "$customN days" else o.label
                PeriodOption.MONTHS -> if (periodOption == o) "$customN months" else o.label
                else -> o.label
            }
        }) { o ->
            if (o == PeriodOption.DAYS || o == PeriodOption.MONTHS) askCustomPeriod = o else periodOption = o
        }
        SectionLabel("Time range")
        ChipRow(RangeOption.entries, rangeOption, label = { o ->
            if (o == RangeOption.CUSTOM && rangeOption == o) "${Fmt.date(from)} – ${Fmt.date(to)}" else o.label
        }) { o -> if (o == RangeOption.CUSTOM) askCustomRange = true else rangeOption = o }

        val (data, summary) = result.getOrElse { e ->
            Text(
                e.message ?: "Can't draw this chart",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        val typeNames = selectedTypes.map { id -> snapshot.type(id)?.name ?: "?" }
        val colors = selectedTypes.map { id -> snapshot.type(id)?.composeColor ?: Color.Gray }

        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${typeNames.joinToString(" & ")} per ${Stats.periodName(period)}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val readout = selectedBucket?.let { i -> data.buckets.getOrNull(i) }?.let { b ->
                    val i = selectedBucket!!
                    Stats.longLabel(b) + ": " + data.series.mapIndexed { s, series ->
                        (if (data.series.size > 1) "${typeNames[s]} " else "") + series.counts[i]
                    }.joinToString(" · ")
                } ?: "Tap or drag on the chart to see values · pinch to zoom"
                Text(readout, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                key(data.buckets.size, data.buckets.firstOrNull()) {
                    FrequencyChart(
                        data = data,
                        colors = colors,
                        onSelect = { selectedBucket = it },
                        modifier = Modifier.fillMaxWidth().height(260.dp).padding(top = 8.dp),
                    )
                }
            }
        }

        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            val m = Modifier.weight(1f)
            StatTile("Total in range", summary.total.toString(), m)
            StatTile("Average per ${Stats.periodName(period)}", "%.2f".format(summary.averagePerPeriod), m)
            StatTile("Typical gap", summary.averageGapDays?.let { "every ${Fmt.gap(it)}" } ?: "—", m)
            StatTile("Last time", summary.lastEvent?.let { Fmt.relative(it) } ?: "never", m)
        }

        val inRange = remember(selectedEvents, data) {
            val start = data.buckets.first().start
            val end = data.buckets.last().endExclusive
            selectedEvents.filter {
                val d = Stats.toDate(it.timestamp, zone)
                !d.isBefore(start) && d.isBefore(end)
            }.sortedByDescending { it.timestamp }
        }
        SectionLabel("Occurrences (${inRange.size})")
        if (inRange.isEmpty()) {
            Text(
                "No events in this range.",
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        inRange.take(MAX_LISTED).forEach { e -> OccurrenceRow(e, snapshot) { onOpenEvent(e.id) } }
        if (inRange.size > MAX_LISTED) {
            Text(
                "Showing the latest $MAX_LISTED. See the Events tab for all.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    askCustomPeriod?.let { option ->
        NumberDialog(
            title = if (option == PeriodOption.DAYS) "Count per how many days?" else "Count per how many months?",
            initial = customN,
            max = if (option == PeriodOption.DAYS) 3650 else 240,
            onDismiss = { askCustomPeriod = null },
            onPicked = { customN = it; periodOption = option },
        )
    }
    if (askCustomRange) {
        RangeDialog(
            from = LocalDate.ofEpochDay(customFrom),
            to = LocalDate.ofEpochDay(customTo),
            onDismiss = { askCustomRange = false },
            onPicked = { f, t -> customFrom = f.toEpochDay(); customTo = t.toEpochDay(); rangeOption = RangeOption.CUSTOM },
        )
    }
}

private const val MAX_LISTED = 200

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onClick: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onClick(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OccurrenceRow(e: Event, snapshot: Snapshot, onClick: () -> Unit) {
    val type = snapshot.type(e.typeId)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeAvatar(type, 24.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("${type?.name ?: "?"} · ${Fmt.dateTime(e.timestamp)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (e.attachment != null) Icon(Icons.Filled.AttachFile, "Has file", Modifier.size(18.dp))
    }
    HorizontalDivider(Modifier.padding(start = 52.dp))
}

private val LabelsKey = ExtraStore.Key<List<String>>()
private val LabelSpacingKey = ExtraStore.Key<Int>()

/** Line chart of event counts per period, one line per event type. */
@Composable
fun FrequencyChart(data: FrequencyData, colors: List<Color>, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(data) {
        producer.runTransaction {
            lineSeries { data.series.forEach { s -> series(s.counts) } }
            extras {
                it[LabelsKey] = data.labels
                it[LabelSpacingKey] = ceil(data.buckets.size / 6.0).toInt().coerceAtLeast(1)
            }
        }
    }
    val showPoints = data.buckets.size <= 60
    val lines = remember(colors, showPoints) {
        colors.map { c ->
            LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(fill(c)),
                areaFill = if (colors.size == 1) LineCartesianLayer.AreaFill.single(fill(c.copy(alpha = 0.18f))) else null,
                pointProvider = if (showPoints) {
                    LineCartesianLayer.PointProvider.single(LineCartesianLayer.point(shapeComponent(fill(c), CorneredShape.Pill), 7.dp))
                } else null,
            )
        }
    }
    val maxY = data.maxCount
    val layer = rememberLineCartesianLayer(
        lineProvider = remember(lines) { LineCartesianLayer.LineProvider.series(lines) },
        rangeProvider = remember(maxY) { CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = if (maxY < 1) 1.0 else null) },
    )
    val integer = remember { DecimalFormat("#") }
    val bottomAxis = HorizontalAxis.rememberBottom(
        valueFormatter = remember {
            CartesianValueFormatter { context, x, _ ->
                context.model.extraStore.getOrNull(LabelsKey)?.getOrNull(x.roundToInt())?.ifEmpty { null } ?: " "
            }
        },
        itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned(spacing = { it.getOrNull(LabelSpacingKey) ?: 1 }) },
        guideline = null,
    )
    val startAxis = VerticalAxis.rememberStart(
        valueFormatter = remember { CartesianValueFormatter.decimal(integer) },
        itemPlacer = remember(maxY) { VerticalAxis.ItemPlacer.step({ if (maxY <= 8) 1.0 else null }) },
    )
    val marker = rememberDefaultCartesianMarker(
        label = rememberTextComponent(
            color = MaterialTheme.colorScheme.onSurface,
            textSize = 12.sp,
            padding = insets(8.dp, 4.dp),
            background = rememberShapeComponent(fill(MaterialTheme.colorScheme.surfaceContainerHighest), CorneredShape.rounded(allDp = 6f)),
        ),
        valueFormatter = remember(colors.size) { DefaultCartesianMarker.ValueFormatter.default(integer, colorCode = colors.size > 1) },
        guideline = rememberAxisGuidelineComponent(),
    )
    val listener = remember(onSelect) {
        object : CartesianMarkerVisibilityListener {
            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                onSelect(targets.firstOrNull()?.x?.roundToInt())
            }

            override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                onSelect(targets.firstOrNull()?.x?.roundToInt())
            }

            override fun onHidden(marker: CartesianMarker) = Unit
        }
    }
    val initialZoom = remember { Zoom.Content }
    val maxZoom = remember { Zoom.max(Zoom.fixed(4f), Zoom.Content) }
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                layer,
                startAxis = startAxis,
                bottomAxis = bottomAxis,
                marker = marker,
                markerVisibilityListener = listener,
            ),
            modelProducer = producer,
            modifier = modifier,
            scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End),
            zoomState = rememberVicoZoomState(initialZoom = initialZoom, minZoom = initialZoom, maxZoom = maxZoom),
        )
    }
}

@Composable
private fun NumberDialog(title: String, initial: Int, max: Int, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val n = text.toIntOrNull()
    val valid = n != null && n in 1..max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(4) },
                singleLine = true,
                isError = !valid,
                supportingText = { Text("1 – $max") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onPicked(n!!); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDialog(from: LocalDate, to: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate, LocalDate) -> Unit) {
    fun LocalDate.millis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.date() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
    val state = rememberDateRangePickerState(initialSelectedStartDateMillis = from.millis(), initialSelectedEndDateMillis = to.millis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                onClick = {
                    onPicked(state.selectedStartDateMillis!!.date(), state.selectedEndDateMillis!!.date())
                    onDismiss()
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        Box(Modifier.widthIn(max = 560.dp).height(480.dp)) {
            DateRangePicker(state, title = { Text("Chart range", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}
