package net.babybuddy.next.widget

import kotlinx.coroutines.flow.MutableStateFlow
import net.babybuddy.next.domain.model.EventType

/**
 * Widget → app deep-link channel (spec §7.1/§7.2). A StateFlow ON PURPOSE: the launcher fires
 * the PendingIntent while the app may be cold (no DashboardViewModel yet) — the request is held
 * until the dashboard's collector consumes it (login gates included) and then cleared.
 *
 * NOTE: threading this through composition parameters does NOT work — NavHost remembers the
 * destination content lambdas, so outer recompositions never re-capture (the reason the first
 * deep-link attempt silently no-oped).
 */
object WidgetDeepLink {

    val panelRequest = MutableStateFlow<EventType?>(null)

    fun requestPanel(type: EventType) {
        panelRequest.value = type
    }

    fun consume() {
        panelRequest.value = null
    }
}
