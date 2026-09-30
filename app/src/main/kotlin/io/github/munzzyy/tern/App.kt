package io.github.munzzyy.tern

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import io.github.munzzyy.tern.data.AppLanguage
import io.github.munzzyy.tern.data.CrashReport
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.widget.Surfaces

class App : Application() {
    val engine: Engine by lazy { createEngine(this).also { Surfaces.start(this, it) } }

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        AppLanguage.applyTo(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.applyTo(this)
    }
}

val Context.engine: Engine get() = (applicationContext as App).engine
