package net.babybuddy.next.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.babybuddy.next.App
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.ui.theme.BabyBuddyNextTheme

/**
 * Configure screen for the QuickActions widget (spec §7.3): child + four slots (default
 * Sleep / Bottle / Diaper / Pump). Launched by the launcher with EXTRA_APPWIDGET_ID
 * (long-press → Configure). Save writes the widget's Preferences state and re-renders it.
 */
class QuickActionsConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Cancelled config must not leave a widget behind (standard appwidget protocol).
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        val container = (application as App).container
        setContent {
            BabyBuddyNextTheme {
                var childId by remember { mutableStateOf<Long?>(null) }
                var slots by remember { mutableStateOf(QuickActionsWidget.DEFAULT_SLOTS) }
                val children by container.childRepository.children
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                LaunchedEffect(children) {
                    if (childId == null) childId = children.firstOrNull()?.id
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Quick actions widget", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Sleep, Pump, and Tummy start their timer with one tap; the others open the app.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )

                    ConfigSectionLabel("Child")
                    ChildPicker(
                        children = children.map { it.id to it.firstName },
                        selected = childId,
                        onSelect = { childId = it },
                    )

                    (0 until 4).forEach { index ->
                        ConfigSectionLabel("Button ${index + 1}")
                        EventTypePicker(
                            options = EventType.entries,
                            selected = slots[index],
                            onSelect = { selected ->
                                slots = slots.toMutableList().also { it[index] = selected }
                            },
                        )
                    }

                    Button(
                        onClick = {
                            val id = childId ?: return@Button
                            lifecycleScope.launch {
                                val glanceId = GlanceAppWidgetManager(this@QuickActionsConfigActivity)
                                    .getGlanceIdBy(appWidgetId)
                                QuickActionsWidget.saveConfig(
                                    this@QuickActionsConfigActivity,
                                    glanceId,
                                    id,
                                    slots,
                                )
                                setResult(
                                    RESULT_OK,
                                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                                )
                                finish()
                            }
                        },
                        enabled = childId != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
