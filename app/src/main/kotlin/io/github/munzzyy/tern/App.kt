package io.github.munzzyy.tern

import android.app.Application
import android.content.Context
import io.github.munzzyy.tern.engine.Engine

class App : Application() {
    val engine: Engine by lazy { createEngine(this) }
}

val Context.engine: Engine get() = (applicationContext as App).engine
