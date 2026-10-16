package net.babybuddy.next.ui

/**
 * Test tags for QA automation (resource-ids in debug builds; see MainActivity).
 * Naming: `screen/element`. Stage 2+ must keep adding here, never inline strings.
 */
object TestTags {
    // Login (UX spec §1)
    const val LOGIN = "login"
    const val LOGIN_SERVER_URL = "login/server_url"
    const val LOGIN_USERNAME = "login/username"
    const val LOGIN_PASSWORD = "login/password"
    const val LOGIN_SUBMIT = "login/submit"
    const val LOGIN_ERROR_BANNER = "login/error_banner"
    const val LOGIN_CLEARTEXT_DIALOG = "login/cleartext_dialog"

    // Dashboard (placeholder until Stage 2)
    const val DASHBOARD = "dashboard"
    const val DASHBOARD_CHILD_SWITCHER = "dashboard/child_switcher"
    const val DASHBOARD_TIMELINE = "dashboard/timeline"
    const val DASHBOARD_REFRESH = "dashboard/refresh"
}
