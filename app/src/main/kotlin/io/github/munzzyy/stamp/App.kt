package io.github.munzzyy.stamp

import android.app.Application
import android.content.Context
import io.github.munzzyy.stamp.engine.Engine

class App : Application() {
    val engine: Engine by lazy { createEngine(this) }
}

val Context.engine: Engine get() = (applicationContext as App).engine
