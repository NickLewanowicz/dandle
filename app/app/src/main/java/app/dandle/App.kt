package app.dandle

import android.app.Application
import android.os.SystemClock
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.dandle.data.AppContainer
import app.dandle.log.BbLog
import okio.Path.Companion.toPath

class App : Application(), Configuration.Provider {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var container: AppContainer
        private set

    /**
     * On-demand WorkManager init (perf: cold start): the default androidx.startup initializer
     * is removed from the manifest, so WorkManager builds here on the first getInstance —
     * which App.onCreate routes through [applicationScope], off the main/first-frame path.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // BB/START milestones: cold-start attribution (logcat -v time gives ms resolution).
        val t0 = SystemClock.elapsedRealtime()
        container = AppContainer(
            appScope = applicationScope,
            sessionFile = filesDir.resolve("session.preferences_pb").absolutePath.toPath(),
            quickActionsFile = filesDir.resolve("quick_actions.preferences_pb").absolutePath.toPath(),
        )
        val t1 = SystemClock.elapsedRealtime()
        container.initDatabase(this)
        val t2 = SystemClock.elapsedRealtime()
        container.start()
        val t3 = SystemClock.elapsedRealtime()
        // Deferred (background): triggers WorkManager's on-demand init off the main thread and
        // re-arms the 15-minute widget safety net, then warms the lazy network/DB singletons so
        // their class-loading never lands on the main thread's content path. Nothing on the
        // first-frame path needs any of it.
        applicationScope.launch {
            app.dandle.widget.WidgetPeriodicWorker.schedule(this@App)
            container.warmStartupSingletons()
        }
        val t4 = SystemClock.elapsedRealtime()
        BbLog.d(
            BbLog.TAG_START,
            "app onCreate ${t4 - t0}ms (container=${t1 - t0} db=${t2 - t1} start=${t3 - t2} worker=deferred ${t4 - t3}ms)",
        )
    }
}
