package net.babybuddy.next.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.babybuddy.next.data.api.ApiException
import net.babybuddy.next.data.db.BabyBuddyDatabase
import net.babybuddy.next.data.mapper.toEntity
import net.babybuddy.next.data.mapper.toModel
import net.babybuddy.next.domain.model.Child
import retrofit2.HttpException

/**
 * Children cache: the Room table is the UI's source of truth; [refresh] pulls the server list
 * behind it (brief 01 §6). All errors are surfaced as a typed result — offline is a normal
 * path, the cached list simply stays.
 */
class ChildRepository(
    private val apiProvider: () -> net.babybuddy.next.data.api.BabyBuddyApi,
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
            val page = apiProvider().listChildren(limit = 100)
            db.childDao().upsertAll(page.results.map { it.toEntity() })
            RefreshResult.Success
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) {
                RefreshResult.Failure(RefreshResult.Reason.AUTH_EXPIRED)
            } else {
                RefreshResult.Failure(RefreshResult.Reason.HTTP_ERROR)
            }
        } catch (e: HttpException) {
            RefreshResult.Failure(RefreshResult.Reason.HTTP_ERROR)
        } catch (e: java.io.IOException) {
            RefreshResult.Failure(RefreshResult.Reason.UNREACHABLE)
        }
    }

    suspend fun clear() {
        db.childDao().clear()
    }
}
