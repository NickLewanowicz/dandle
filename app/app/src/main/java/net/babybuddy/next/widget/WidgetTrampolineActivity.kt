package net.babybuddy.next.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.babybuddy.next.App
import net.babybuddy.next.MainActivity
import net.babybuddy.next.domain.model.EventType

/**
 * Transparent trampoline (spec §7.2): the CareDashboard nap row starts the sleep timer without
 * opening the app; when a timer is already running the tap opens the plain app instead (the
 * >10s stop guard is an in-app dialog — widgets cannot host it mid-launch; documented for QA in
 * docs/flows/new-app/widgets.md). Finishes immediately so the launcher never flickers.
 */
class WidgetTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.getStringExtra(WidgetActions.EXTRA_ACTION)
        val app = application as App
        val container = app.container
        val lifecycle: kotlinx.coroutines.CoroutineScope = (this as LifecycleOwner).lifecycleScope

        when (action) {
            WidgetActions.ACTION_SLEEP_TOGGLE -> lifecycle.launch {
                val childId = container.quickActionActions.activeChildId()
                val running = childId?.let { container.timerRepository.findRunning(EventType.SLEEP, it) }
                if (childId != null && running == null) {
                    container.timerRepository.start(EventType.SLEEP, childId)
                    WidgetRefresher.refreshAll(applicationContext)
                } else {
                    openApp()
                }
                finish()
            }

            else -> { // Unknown action: plain open so the tap is never a dead end.
                openApp()
                finish()
            }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
