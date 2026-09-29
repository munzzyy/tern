package io.github.munzzyy.jackdaw

import android.app.Application
import android.content.Context
import io.github.munzzyy.jackdaw.engine.Engine

class App : Application() {
    val engine: Engine by lazy { createEngine(this) }
}

val Context.engine: Engine get() = (applicationContext as App).engine
