package io.github.munzzyy.jackdaw.ui

import androidx.compose.runtime.mutableStateOf

sealed interface Route {
    data object Apps : Route

    /** [nonce] makes a second share of the same text start a fresh detect. */
    data class Add(val input: String? = null, val nonce: Long = 0) : Route

    data class Detail(val appId: String) : Route

    data object Activity : Route

    data object Settings : Route

    data object Import : Route
}

enum class Tab { APPS, ADD, ACTIVITY, SETTINGS }

fun Route.tab(): Tab = when (this) {
    Route.Apps, is Route.Detail -> Tab.APPS
    is Route.Add -> Tab.ADD
    Route.Activity -> Tab.ACTIVITY
    Route.Settings, Route.Import -> Tab.SETTINGS
}

private const val SEP = '\u0001'

fun encodeRoute(route: Route): String = when (route) {
    Route.Apps -> "apps"
    is Route.Add -> "add$SEP${route.nonce}$SEP${route.input.orEmpty()}"
    is Route.Detail -> "detail$SEP${route.appId}"
    Route.Activity -> "activity"
    Route.Settings -> "settings"
    Route.Import -> "import"
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

    fun encode(): List<String> = routes.map(::encodeRoute)

    companion object {
        fun decode(saved: List<String>): BackStack = BackStack(saved.mapNotNull(::decodeRoute))
    }
}
