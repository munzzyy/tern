package io.github.munzzyy.tern

import android.content.Context
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.engine.real.RealEngine

fun createEngine(context: Context): Engine = RealEngine.shared(context)
