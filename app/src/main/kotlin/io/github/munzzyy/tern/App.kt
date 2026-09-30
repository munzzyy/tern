package io.github.munzzyy.tern

import android.app.Application
import android.content.Context
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.widget.Surfaces

class App : Application() {
    val engine: Engine by lazy { createEngine(this).also { Surfaces.start(this, it) } }
}

val Context.engine: Engine get() = (applicationContext as App).engine
