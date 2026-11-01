package app.dandle.ui

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
    /** The cleartext dialog's confirm button (text alone is ambiguous: step 1's Continue). */
    const val LOGIN_CLEARTEXT_CONTINUE = "login/cleartext_continue"
    const val LOGIN_ADVANCED_TOGGLE = "login/advanced_toggle"
    const val LOGIN_API_KEY = "login/api_key"
    const val LOGIN_CONTINUE = "login/continue"
    const val LOGIN_SERVER_VERIFIED = "login/server_verified"

    // Login rebrand + mode picker (brief 04)
    const val LOGIN_LOGO = "login/logo"
    const val LOGIN_BACKGROUND_ART = "login/background_art"
    const val LOGIN_BACK = "login/back"
    const val LOGIN_MODE_DEVICE = "login/mode_device"
    const val LOGIN_MODE_SERVER = "login/mode_server"
    const val LOGIN_MODE_CLOUD = "login/mode_cloud"
    const val LOGIN_CHILD_NAME = "login/child_name"
    const val LOGIN_CHILD_BIRTH_DATE = "login/child_birth_date"
    /** The birth-date field's trailing "Pick" button (the whole field is also clickable). */
    const val LOGIN_CHILD_DATE_PICK = "login/child_date_pick"
    const val LOGIN_CHILD_SUBMIT = "login/child_submit"
    const val LOGIN_CLOUD_TAB_SIGNIN = "login/cloud_tab/sign_in"
    const val LOGIN_CLOUD_TAB_CREATE = "login/cloud_tab/create"
    // Brief 06 §1: the muted service-honesty line under the cloud lead copy.
    const val LOGIN_CLOUD_SERVICE_NOTICE = "login/cloud_service_notice"
    const val LOGIN_CLOUD_USERNAME = "login/cloud_username"
    // Brief 06 §2: optional contact email on create-account (service-change warnings only).
    const val LOGIN_CONTACT_EMAIL = "login/contact_email"
    const val LOGIN_CLOUD_PASSWORD = "login/cloud_password"
    /** Brief 06 §3: account-password confirmation, side-by-side with the password field. */
    const val LOGIN_CLOUD_PASSWORD_CONFIRM = "login/cloud_password_confirm"
    const val LOGIN_CLOUD_PASSPHRASE = "login/cloud_passphrase"
    // Brief 06 §4: the create-tab passphrase section + generate/verify state machine.
    const val LOGIN_CLOUD_PASSPHRASE_GENERATE = "login/cloud_passphrase_generate"
    const val LOGIN_CLOUD_PASSPHRASE_REGEN = "login/cloud_passphrase_regen"
    const val LOGIN_CLOUD_PASSPHRASE_CUSTOM = "login/cloud_passphrase_custom"
    const val LOGIN_CLOUD_PASSPHRASE_CONTINUE = "login/cloud_passphrase_continue"
    const val LOGIN_CLOUD_PASSPHRASE_VERIFY = "login/cloud_passphrase_verify"
    /** VERIFY step's "Back" TextButton (returns to GENERATE with the passphrase kept). */
    const val LOGIN_CLOUD_PASSPHRASE_BACK = "login/cloud_passphrase_back"
    const val LOGIN_CLOUD_PASSPHRASE_WARNING = "login/cloud_passphrase_warning"
    const val LOGIN_CLOUD_SUBMIT = "login/cloud_submit"

    // Dashboard (UX spec §2)
    const val DASHBOARD = "dashboard"
    const val DASHBOARD_CHILD_SWITCHER = "dashboard/child_switcher"
    const val DASHBOARD_OVERFLOW = "dashboard/overflow"
    const val DASHBOARD_TIMELINE = "dashboard/timeline"
    const val DASHBOARD_REFRESH = "dashboard/refresh"
    const val DASHBOARD_SYNC_CHIP = "dashboard/sync_chip"
    const val DASHBOARD_EMPTY_TIMELINE = "dashboard/empty_timeline"

    /** Parametrized: "dashboard/quick_action/FEEDING" etc. (EventType.name). */
    fun quickAction(type: String) = "dashboard/quick_action/$type"

    /** Parametrized: "dashboard/timer/SLEEP" (EventType.name of the running timer). */
    fun timerCard(type: String) = "dashboard/timer/$type"

    fun timerSave(type: String) = "dashboard/timer/$type/save"


    /** The inline quick-action panel: "dashboard/panel/FEEDING" etc. */
    fun panel(type: String) = "dashboard/panel/$type"

    const val PANEL_SAVE = "dashboard/panel/save"
    const val PANEL_CLOSE = "dashboard/panel/close"
    const val PANEL_NOTE = "dashboard/panel/note"
    const val PANEL_NOTE_TEXT = "dashboard/panel/note_text"
    const val PANEL_AMOUNT = "dashboard/panel/amount"
    const val PANEL_WET = "dashboard/panel/wet"
    const val PANEL_SOLID = "dashboard/panel/solid"
    const val PANEL_MILESTONE = "dashboard/panel/milestone"
    const val PANEL_TEXT = "dashboard/panel/text"

    /** Feeding cascade: the collapsed "Type · Method" summary row (tap = re-open). */
    const val PANEL_FEEDING_SUMMARY = "dashboard/panel/feeding_summary"

    /** The diaper color slider strip (dots carry the per-wire `color/<wire>` tags). */
    const val PANEL_COLOR_SLIDER = "dashboard/panel/color_slider"

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



    const val FAILURE_DIALOG = "edit/failure_dialog"
}
