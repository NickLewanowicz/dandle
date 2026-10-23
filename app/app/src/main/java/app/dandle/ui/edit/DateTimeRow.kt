package app.dandle.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dandle.R
import app.dandle.ui.dashboard.dayLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val compactDateFormat = DateTimeFormatter.ofPattern("MMM d")

/**
 * The standard time-editing row (owner rework U6): the stored value ("Today 14:32") opens the
 * native Material TimePicker pre-populated; the small calendar button opens the native
 * DatePicker (then the TimePicker). No steppers — this is exactly what the Baby Buddy web UI
 * and the platform offer, nothing more. Both entry points are pre-populated with the current
 * value so the common case (nudge the minutes) is tap → adjust → OK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeRow(
    label: String,
    millis: Long,
    onPicked: (Long) -> Unit,
    modifier: Modifier = Modifier,
    valueTag: String? = null,
) {
    var flow by remember { mutableStateOf<Flow>(Flow.Idle) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                onClick = { flow = Flow.Time(date = null) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .then(if (valueTag != null) Modifier.testTag(valueTag) else Modifier),
            ) {
                Text(
                    valueLabel(millis),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        IconButton(onClick = { flow = Flow.Date }) {
            Icon(
                painterResource(R.drawable.ic_bb_calendar),
                contentDescription = "Pick date",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    when (val current = flow) {
        Flow.Idle -> Unit
        Flow.Date -> DatePickerStep(
            millis = millis,
            onDateChosen = { date -> flow = Flow.Time(date = date) },
            onDismiss = { flow = Flow.Idle },
        )
        is Flow.Time -> TimeStep(
            millis = millis,
            date = current.date ?: Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate(),
            onPicked = {
                flow = Flow.Idle
                onPicked(it)
            },
            onDismiss = { flow = Flow.Idle },
        )
    }
}

private sealed interface Flow {
    data object Idle : Flow
    data object Date : Flow

    /** The calendar day the time applies to; null = keep the current value's day. */
    data class Time(val date: LocalDate?) : Flow
}

/** Native date picker; the chosen day flows into the time step (standard Android two-step). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerStep(
    millis: Long,
    onDateChosen: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = millis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                // DatePicker's selection is UTC-noon-based; its UTC calendar date IS the
                // intended LOCAL calendar date (a UTC-midnight round-trip combined with a
                // local atTime shifted the picked day by one after the local/UTC rollover —
                // an evening time came out as TOMORROW).
                val date = datePickerState.selectedDateMillis
                    ?.let(::localDateFromDatePickerMillis)
                    ?: Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                onDateChosen(date)
            }) { Text("Next") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = datePickerState, title = null)
    }
}

/** Pre-populated native time picker, combined with the calendar [date] on OK. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeStep(
    millis: Long,
    date: LocalDate,
    onPicked: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initial = Instant.ofEpochMilli(millis).atZone(zone)
    val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a time") },
        text = { TimePicker(state = timeState) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onPicked(
                    date
                        .atTime(timeState.hour, timeState.minute)
                        .atZone(zone)
                        .toInstant()
                        .toEpochMilli(),
                )
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The calendar day the Material DatePicker selected. Its selection millis are
 * UTC-noon-based, so the UTC calendar date IS the intended LOCAL date — but
 * combining that via a UTC-midnight round-trip into `LocalDate.atTime(...).
 * atZone(local)` shifted the picked day by one for evening times (after the
 * local/UTC midnight rollover, "Today 20:03" picked 19:05 came out TOMORROW
 * and validation refused to save). Pin: DateTimeRowDateTest.
 */
fun localDateFromDatePickerMillis(pickerMillis: Long): LocalDate =
    Instant.ofEpochMilli(pickerMillis).atZone(ZoneOffset.UTC).toLocalDate()

fun valueLabel(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(millis).atZone(zone)
    val day = dayLabel(millis)
    val prefix = if (day == "Today" || day == "Yesterday") day else compactDateFormat.format(dateTime)
    return "$prefix ${timeFormat.format(dateTime)}"
}
