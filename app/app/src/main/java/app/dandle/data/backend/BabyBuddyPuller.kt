package app.dandle.data.backend

import app.dandle.data.api.ApiException
import app.dandle.data.api.BabyBuddyApi
import app.dandle.data.mapper.toEntity
import app.dandle.data.db.ChildEntity
import app.dandle.data.db.EventEntity

/**
 * Baby Buddy pull transport (brief 05 phase 2): the six per-type list calls + children list,
 * extracted from ChildRepository/EventRepository.refresh(). Merge policy stays in the repos;
 * all wire + error mapping lives here.
 */
class BabyBuddyPuller(
    private val apiProvider: () -> BabyBuddyApi,
) : PullGateway {

    override suspend fun listChildren(): List<ChildEntity> = wrap {
        apiProvider().listChildren(limit = 100).results.map { it.toEntity() }
    }

    override suspend fun pullRecentEvents(limitPerType: Int): List<EventEntity> = wrap {
        buildList {
            addAll(apiProvider().listFeedings(limit = limitPerType, ordering = "-end").results.map { it.toEntity() })
            addAll(apiProvider().listChanges(limit = limitPerType, ordering = "-time").results.map { it.toEntity() })
            addAll(apiProvider().listSleep(limit = limitPerType, ordering = "-end").results.map { it.toEntity() })
            addAll(apiProvider().listNotes(limit = limitPerType, ordering = "-time").results.map { it.toEntity() })
            addAll(apiProvider().listPumping(limit = limitPerType, ordering = "-end").results.map { it.toEntity() })
            addAll(apiProvider().listTummyTimes(limit = limitPerType, ordering = "-start").results.map { it.toEntity() })
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
        status == 401 || status == 403 -> BackendError.AuthExpired
        status in 500..599 -> BackendError.ServerError(status)
        else -> BackendError.Http(status, rawBody)
    }
}
