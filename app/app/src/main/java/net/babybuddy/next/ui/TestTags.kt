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
    const val LOGIN_ADVANCED_TOGGLE = "login/advanced_toggle"
    const val LOGIN_API_KEY = "login/api_key"

    // Dashboard (UX spec §2)
    const val DASHBOARD = "dashboard"
    const val DASHBOARD_CHILD_SWITCHER = "dashboard/child_switcher"
    const val DASHBOARD_OVERFLOW = "dashboard/overflow"
    const val DASHBOARD_TIMELINE = "dashboard/timeline"
    const val DASHBOARD_REFRESH = "dashboard/refresh"
    const val DASHBOARD_SYNC_CHIP = "dashboard/sync_chip"
    const val DASHBOARD_TIMER_STRIP = "dashboard/timer_strip"
    const val DASHBOARD_EMPTY_TIMELINE = "dashboard/empty_timeline"

    /** Parametrized: "dashboard/quick_action/FEEDING" etc. (EventType.name). */
    fun quickAction(type: String) = "dashboard/quick_action/$type"

    /** Parametrized: "dashboard/timer/SLEEP" (EventType.name of the running timer). */
    fun timerCard(type: String) = "dashboard/timer/$type"

    fun timerSave(type: String) = "dashboard/timer/$type/save"

    fun timerDiscard(type: String) = "dashboard/timer/$type/discard"

    /** The inline quick-action panel: "dashboard/panel/FEEDING" etc. */
    fun panel(type: String) = "dashboard/panel/$type"

    const val PANEL_SAVE = "dashboard/panel/save"
    const val PANEL_CLOSE = "dashboard/panel/close"
    const val PANEL_NOTE = "dashboard/panel/note"
    const val PANEL_NOTE_TEXT = "dashboard/panel/note_text"
    const val PANEL_AMOUNT = "dashboard/panel/amount"
    const val PANEL_WET = "dashboard/panel/wet"
    const val PANEL_SOLID = "dashboard/panel/solid"
    const val PANEL_EXTRA_OPTIONS = "dashboard/panel/extra_options"
    const val PANEL_MILESTONE = "dashboard/panel/milestone"
    const val PANEL_TEXT = "dashboard/panel/text"

    // T2: "started X min ago" adjust row on timed panels
    const val PANEL_STARTED_AGO = "dashboard/panel/started_ago"
    const val PANEL_STARTED_ADJUST = "dashboard/panel/started_adjust"
    const val PANEL_STARTED_PICK = "dashboard/panel/started_pick"

    /** Parametrized: "dashboard/panel/started_step/-15m" etc. */
    fun startedAgoStep(label: String) = "dashboard/panel/started_step/$label"

    fun feedingTypeChip(wire: String) = "dashboard/panel/feeding_type/$wire"

    fun feedingMethodChip(wire: String) = "dashboard/panel/feeding_method/$wire"

    fun diaperColorChip(wire: String) = "dashboard/panel/color/$wire"

    // Dialogs
    const val STOP_DIALOG = "dashboard/stop_dialog"
    const val STOP_DIALOG_SAVE = "dashboard/stop_dialog/save"
    const val STOP_DIALOG_DISCARD = "dashboard/stop_dialog/discard"
    const val STOP_DIALOG_KEEP = "dashboard/stop_dialog/keep"
    const val DUPLICATE_DIALOG = "dashboard/duplicate_dialog"
    const val DUPLICATE_ADD = "dashboard/duplicate_dialog/add"

    // Timeline rows/badges (UX spec §2/§5)
    fun timelineRow(localUuid: String) = "dashboard/timeline/$localUuid"

    const val BADGE_PENDING = "dashboard/badge_pending"
    const val BADGE_FAILED = "dashboard/badge_failed"
    const val DAY_HEADER = "dashboard/day_header"

    // Quick Edit sheet (Stage 3, UX spec §4)
    const val EDIT_SHEET = "edit/sheet"
    const val EDIT_SAVE = "edit/save"
    const val EDIT_RETRY = "edit/retry"
    const val EDIT_DELETE = "edit/delete"
    const val EDIT_VALIDATION = "edit/validation"
    const val EDIT_DELETE_CONFIRM = "edit/delete_confirm"

    fun editStepperMinus(field: String) = "edit/stepper/${field}_minus"

    fun editStepperPlus(field: String) = "edit/stepper/${field}_plus"

    const val FAILURE_DIALOG = "edit/failure_dialog"
}
