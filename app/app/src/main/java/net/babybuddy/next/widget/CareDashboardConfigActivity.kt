package net.babybuddy.next.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
 * Configure screen for the CareDashboard widget (spec §7.3): child + row visibility.
 */
class CareDashboardConfigActivity : ComponentActivity() {

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
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        val container = (application as App).container
        val allRows = listOf(EventType.FEEDING, EventType.CHANGE, EventType.SLEEP, EventType.PUMPING)
        setContent {
            BabyBuddyNextTheme {
                var childId by remember { mutableStateOf<Long?>(null) }
                var visible by remember {
                    mutableStateOf(allRows.toSet())
                }
                val children by container.childRepository.children
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                LaunchedEffect(children) {
                    if (childId == null) childId = children.firstOrNull()?.id
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Care dashboard widget", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Shows the last feed, diaper, nap, and pump for one child.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )

                    ConfigSectionLabel("Child")
                    ChildPicker(
                        children = children.map { it.id to it.firstName },
                        selected = childId,
                        onSelect = { childId = it },
                    )

                    ConfigSectionLabel("Rows")
                    VisibilityPicker(
                        options = allRows,
                        visible = visible,
                        onToggle = { type ->
                            visible = if (type in visible) visible - type else visible + type
                        },
                    )

                    Button(
                        onClick = {
                            val id = childId ?: return@Button
                            lifecycleScope.launch {
                                val glanceId = GlanceAppWidgetManager(this@CareDashboardConfigActivity)
                                    .getGlanceIdBy(appWidgetId)
                                CareDashboardWidget.saveConfig(
                                    this@CareDashboardConfigActivity,
                                    glanceId,
                                    id,
                                    visible,
                                )
                                setResult(
                                    RESULT_OK,
                                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                                )
                                finish()
                            }
                        },
                        enabled = childId != null && visible.isNotEmpty(),
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
