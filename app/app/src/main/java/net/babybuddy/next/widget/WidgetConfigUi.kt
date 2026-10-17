package net.babybuddy.next.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.babybuddy.next.domain.model.EventType

/** Shared widgets for the two configure screens (spec §7.3). */

@Composable
fun ConfigSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun ChildPicker(
    children: List<Pair<Long, String>>,
    selected: Long?,
    onSelect: (Long) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        children.forEach { (id, name) ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
fun EventTypePicker(
    options: List<EventType>,
    selected: EventType,
    onSelect: (EventType) -> Unit,
) {
    Column {
        options.chunked(3).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { type ->
                    FilterChip(
                        selected = selected == type,
                        onClick = { onSelect(type) },
                        label = { Text(WidgetStateMapper.slotLabel(type)) },
                    )
                }
            }
        }
    }
}

@Composable
fun VisibilityPicker(
    options: List<EventType>,
    visible: Set<EventType>,
    onToggle: (EventType) -> Unit,
) {
    Column {
        options.chunked(2).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { type ->
                    FilterChip(
                        selected = type in visible,
                        onClick = { onToggle(type) },
                        label = { Text(WidgetStateMapper.rowLabel(type)) },
                    )
                }
            }
        }
    }
}
