package app.dandle.data.api

import app.dandle.domain.DrfError
import app.dandle.domain.ConflictPolicy
import retrofit2.HttpException

/**
 * Typed non-2xx API failure. [validation] carries the parsed DRF 400 body (field errors +
 * non_field_errors + conflicting-entry reference) when the server sent one.
 */
class ApiException(
    val status: Int,
    val rawBody: String?,
    val validation: DrfError?,
) : Exception("HTTP $status${validation?.let { ": ${it.displayReason}" } ?: ""}") {

    companion object {
        fun from(e: HttpException): ApiException {
            val body = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            return ApiException(
                status = e.code(),
                rawBody = body,
                validation = ConflictPolicy.parse400Body(body),
            )
        }
    }
}
