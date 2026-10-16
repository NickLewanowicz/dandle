package net.babybuddy.next

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.babybuddy.next.data.AppContainer
import okio.Path.Companion.toPath

class App : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            appScope = applicationScope,
            sessionFile = filesDir.resolve("session.preferences_pb").absolutePath.toPath(),
            quickActionsFile = filesDir.resolve("quick_actions.preferences_pb").absolutePath.toPath(),
        )
        container.initDatabase(this)
        container.start()
    }
}
