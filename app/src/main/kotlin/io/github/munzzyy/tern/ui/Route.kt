package io.github.munzzyy.tern.ui

import androidx.compose.runtime.mutableStateOf

sealed interface Route {
    data object Apps : Route

    /** [nonce] makes a second share of the same text start a fresh detect. */
    data class Add(val input: String? = null, val nonce: Long = 0) : Route

    data class Detail(val appId: String) : Route

    data object Activity : Route

    data object Settings : Route

    data object Import : Route

    data object Look : Route

    /** The open door for a phone. It is open for as long as this route is on screen. */
    data object Handoff : Route
}

enum class Tab { APPS, ADD, ACTIVITY, SETTINGS }

fun Route.tab(): Tab = when (this) {
    Route.Apps, is Route.Detail -> Tab.APPS
    is Route.Add, Route.Handoff -> Tab.ADD
    Route.Activity -> Tab.ACTIVITY
    Route.Settings, Route.Import, Route.Look -> Tab.SETTINGS
}

private const val SEP = '\u0001'

fun encodeRoute(route: Route): String = when (route) {
    Route.Apps -> "apps"
    is Route.Add -> "add$SEP${route.nonce}$SEP${route.input.orEmpty()}"
    is Route.Detail -> "detail$SEP${route.appId}"
    Route.Activity -> "activity"
    Route.Settings -> "settings"
    Route.Import -> "import"
    Route.Look -> "look"
    Route.Handoff -> "handoff"
}

fun decodeRoute(s: String): Route? {
    val parts = s.split(SEP, limit = 3)
    return when (parts[0]) {
        "apps" -> Route.Apps
        "add" -> Route.Add(parts.getOrNull(2)?.takeIf { it.isNotEmpty() }, parts.getOrNull(1)?.toLongOrNull() ?: 0)
        "detail" -> parts.getOrNull(1)?.let { Route.Detail(it) }
        "activity" -> Route.Activity
        "settings" -> Route.Settings
        "import" -> Route.Import
        "look" -> Route.Look
        "handoff" -> Route.Handoff
        else -> null
    }
}

/** A plain list of routes; the last one is on screen. The first is always Apps. */
class BackStack(initial: List<Route>) {
    private val state = mutableStateOf(if (initial.firstOrNull() == Route.Apps) initial else listOf(Route.Apps) + initial)

    var routes: List<Route>
        get() = state.value
        private set(value) {
            state.value = value
        }

    val top: Route get() = routes.last()
    val canPop: Boolean get() = routes.size > 1

    fun push(route: Route) {
        routes = if (top == route) routes else routes + route
    }

    fun pop(): Boolean {
        if (!canPop) return false
        routes = routes.dropLast(1)
        return true
    }

    /** Switching tabs starts that tab's history over; Apps sits under every tab so back always lands there. */
    fun select(tab: Tab) {
        routes = when (tab) {
            Tab.APPS -> listOf(Route.Apps)
            Tab.ADD -> listOf(Route.Apps, Route.Add())
            Tab.ACTIVITY -> listOf(Route.Apps, Route.Activity)
            Tab.SETTINGS -> listOf(Route.Apps, Route.Settings)
        }
    }

    /** Showing a different app replaces the detail on top instead of stacking details. */
    fun showDetail(appId: String) {
        val base = if (top is Route.Detail) routes.dropLast(1) else routes
        routes = base + Route.Detail(appId)
    }

    fun openAdd(input: String?, nonce: Long) {
        routes = listOf(Route.Apps, Route.Add(input, nonce))
    }

    /** The Add screen was opened on top of another screen when more than the list lies under it. */
    val addIsOnTop: Boolean get() = top is Route.Add && routes.size > 2

    /** After an app was added, its detail takes the place of the Add screen. Back returns to whatever offered the link. */
    fun showAdded(appId: String) {
        routes = if (addIsOnTop) routes.dropLast(1) + Route.Detail(appId) else listOf(Route.Apps, Route.Detail(appId))
    }

    /** Looks [input] up on top of the screen that offered it, so that back returns there. */
    fun lookAt(input: String, nonce: Long) {
        val base = if (top is Route.Add) routes.dropLast(1) else routes
        routes = base + Route.Add(input, nonce)
    }

    fun encode(): List<String> = routes.map(::encodeRoute)

    companion object {
        fun decode(saved: List<String>): BackStack = BackStack(saved.mapNotNull(::decodeRoute))
    }
}
