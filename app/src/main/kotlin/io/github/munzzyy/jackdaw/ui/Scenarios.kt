package io.github.munzzyy.jackdaw.ui

/** Implemented only by the debug build's stand-in engine, to switch between sets of invented apps. */
interface Scenarios {
    fun loadScenario(name: String)
}

const val EXTRA_SCENARIO = "scenario"
const val SCENARIO_FIRST_RUN = "firstrun"
