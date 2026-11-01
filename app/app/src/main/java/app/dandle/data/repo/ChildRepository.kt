package app.dandle.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.dandle.data.backend.BackendError
import app.dandle.data.backend.BackendException
import app.dandle.data.backend.PullGateway
import app.dandle.data.db.BabyBuddyDatabase
import app.dandle.data.mapper.toModel
import app.dandle.domain.model.Child

/**
 * Children cache: the Room table is the UI's source of truth; [refresh] pulls the server list
 * behind it via [PullGateway] (brief 01 §6, brief 05 §1). All errors are surfaced as a typed
 * result — offline is a normal path, the cached list simply stays.
 */
class ChildRepository(
    private val puller: PullGateway,
    private val db: BabyBuddyDatabase,
) {

    val children: Flow<List<Child>> = db.childDao().observeAll().map { list -> list.map { it.toModel() } }

    sealed interface RefreshResult {
        data object Success : RefreshResult
        data class Failure(val reason: Reason) : RefreshResult

        enum class Reason { UNREACHABLE, AUTH_EXPIRED, HTTP_ERROR }
    }

    suspend fun refresh(): RefreshResult {
        return try {
            db.childDao().upsertAll(puller.listChildren())
            RefreshResult.Success
        } catch (e: BackendException) {
            when (e.error) {
                is BackendError.Unreachable -> RefreshResult.Failure(RefreshResult.Reason.UNREACHABLE)
                is BackendError.AuthExpired -> RefreshResult.Failure(RefreshResult.Reason.AUTH_EXPIRED)
                else -> RefreshResult.Failure(RefreshResult.Reason.HTTP_ERROR)
            }
        } catch (e: java.io.IOException) {
            RefreshResult.Failure(RefreshResult.Reason.UNREACHABLE)
        }
    }

    suspend fun clear() {
        db.childDao().clear()
    }
}
