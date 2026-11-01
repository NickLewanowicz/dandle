package app.dandle.data.backend

import app.dandle.data.api.ApiException
import app.dandle.data.api.BabyBuddyApi
import app.dandle.domain.model.EventType

/**
 * Baby Buddy timer mirror (spec §3): server timers named "{Type}-BBapp:<n>" so running
 * timers show in the web UI. All steps are best-effort at the call sites; errors surface
 * as [BackendError].
 */
class BabyBuddyTimerMirror(
    private val apiProvider: () -> BabyBuddyApi,
) : TimerMirror {

    override suspend fun create(childId: Long, kind: EventType, startIso: String): Long = wrap {
        apiProvider().createTimer(
            app.dandle.data.api.TimerWriteDto(
                child = childId,
                name = kind.serverTimerName,
                start = startIso,
            )
        ).id
    }

    override suspend fun delete(serverTimerId: Long): Unit = wrap {
        val response = apiProvider().deleteTimer(serverTimerId)
        check(response.isSuccessful || response.code() == 404)
    }

    override suspend fun listForChild(childId: Long): List<ServerTimer> = wrap {
        apiProvider().listTimers(child = childId, limit = 100).results.map {
            ServerTimer(id = it.id, name = it.name, childId = it.child, startIso = it.start)
        }
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw BackendException(e.toBackendError())
    } catch (e: retrofit2.HttpException) {
        throw BackendException(ApiException.from(e).toBackendError())
    } catch (e: java.io.IOException) {
        throw BackendException(BackendError.Unreachable)
    }

    private fun ApiException.toBackendError(): BackendError = when {
        status in 500..599 -> BackendError.ServerError(status)
        else -> BackendError.Http(status, rawBody)
    }
}
