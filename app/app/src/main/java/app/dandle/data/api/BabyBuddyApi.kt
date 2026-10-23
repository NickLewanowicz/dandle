package app.dandle.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Baby Buddy REST API (docs/research/babybuddy-api.md §3).
 *
 * - Endpoints live directly under /api/ (NO /api/v1/ prefix); list + detail routes carry
 *   trailing slashes; /api/profile deliberately does NOT.
 * - PUT is 405 server-wide — PATCH for partial updates.
 * - POST = 201, PATCH = 200, DELETE = 204 (empty body).
 * - List filters used by the app: `ordering`, `limit`, `offset`, `child` (plus per-type extras).
 *
 * The base URL is a placeholder; [app.dandle.data.api.BaseUrlInterceptor] rewrites
 * scheme/host/port per request from the session's server URL.
 */
interface BabyBuddyApi {

    // -- Children ------------------------------------------------------------

    @GET("children/")
    suspend fun listChildren(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): PaginatedDto<ChildDto>

    // -- Diaper changes -------------------------------------------------------

    @GET("changes/")
    suspend fun listChanges(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("child") child: Long? = null,
        @Query("ordering") ordering: String? = null,
    ): PaginatedDto<ChangeDto>

    @POST("changes/")
    suspend fun createChange(@Body body: ChangeWriteDto): ChangeDto

    @PATCH("changes/{id}/")
    suspend fun patchChange(@Path("id") id: Long, @Body body: ChangeWriteDto): ChangeDto

    @DELETE("changes/{id}/")
    suspend fun deleteChange(@Path("id") id: Long): Response<Unit>

    // -- Feedings -------------------------------------------------------------

    @GET("feedings/")
    suspend fun listFeedings(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("child") child: Long? = null,
        @Query("ordering") ordering: String? = null,
    ): PaginatedDto<FeedingDto>

    @POST("feedings/")
    suspend fun createFeeding(@Body body: FeedingWriteDto): FeedingDto

    @PATCH("feedings/{id}/")
    suspend fun patchFeeding(@Path("id") id: Long, @Body body: FeedingWriteDto): FeedingDto

    @DELETE("feedings/{id}/")
    suspend fun deleteFeeding(@Path("id") id: Long): Response<Unit>

    // -- Sleep ----------------------------------------------------------------

    @GET("sleep/")
    suspend fun listSleep(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("child") child: Long? = null,
        @Query("ordering") ordering: String? = null,
    ): PaginatedDto<SleepDto>

    @POST("sleep/")
    suspend fun createSleep(@Body body: SleepWriteDto): SleepDto

    @PATCH("sleep/{id}/")
    suspend fun patchSleep(@Path("id") id: Long, @Body body: SleepWriteDto): SleepDto

    @DELETE("sleep/{id}/")
    suspend fun deleteSleep(@Path("id") id: Long): Response<Unit>

    // -- Notes ----------------------------------------------------------------

    @GET("notes/")
    suspend fun listNotes(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("child") child: Long? = null,
        @Query("ordering") ordering: String? = null,
    ): PaginatedDto<NoteDto>

    @POST("notes/")
    suspend fun createNote(@Body body: NoteWriteDto): NoteDto

    @PATCH("notes/{id}/")
    suspend fun patchNote(@Path("id") id: Long, @Body body: NoteWriteDto): NoteDto

    @DELETE("notes/{id}/")
    suspend fun deleteNote(@Path("id") id: Long): Response<Unit>

    // -- Pumping --------------------------------------------------------------

    @GET("pumping/")
    suspend fun listPumping(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("child") child: Long? = null,
        @Query("ordering") ordering: String? = null,
    ): PaginatedDto<PumpingDto>

    @POST("pumping/")
    suspend fun createPumping(@Body body: PumpingWriteDto): PumpingDto

    @PATCH("pumping/{id}/")
    suspend fun patchPumping(@Path("id") id: Long, @Body body: PumpingWriteDto): PumpingDto

    @DELETE("pumping/{id}/")
    suspend fun deletePumping(@Path("id") id: Long): Response<Unit>

    // -- Tummy times ----------------------------------------------------------

    @GET("tummy-times/")
    suspend fun listTummyTimes(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("child") child: Long? = null,
        @Query("ordering") ordering: String? = null,
    ): PaginatedDto<TummyTimeDto>

    @POST("tummy-times/")
    suspend fun createTummyTime(@Body body: TummyTimeWriteDto): TummyTimeDto

    @PATCH("tummy-times/{id}/")
    suspend fun patchTummyTime(@Path("id") id: Long, @Body body: TummyTimeWriteDto): TummyTimeDto

    @DELETE("tummy-times/{id}/")
    suspend fun deleteTummyTime(@Path("id") id: Long): Response<Unit>

    // -- Timers ---------------------------------------------------------------
    // Start = POST; stop = DELETE (row is deleted server-side); restart = PATCH .../restart/.

    @GET("timers/")
    suspend fun listTimers(
        @Query("child") child: Long? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): PaginatedDto<TimerDto>

    @POST("timers/")
    suspend fun createTimer(@Body body: TimerWriteDto): TimerDto

    @PATCH("timers/{id}/")
    suspend fun patchTimer(@Path("id") id: Long, @Body body: TimerWriteDto): TimerDto

    @PATCH("timers/{id}/restart/")
    suspend fun restartTimer(@Path("id") id: Long): TimerDto

    @DELETE("timers/{id}/")
    suspend fun deleteTimer(@Path("id") id: Long): Response<Unit>

    // -- Profile (no trailing slash!) ------------------------------------------

    @GET("profile")
    suspend fun profile(): ProfileDto
}
