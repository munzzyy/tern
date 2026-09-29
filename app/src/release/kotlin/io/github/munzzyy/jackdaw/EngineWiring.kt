package io.github.munzzyy.jackdaw

import android.content.Context
import io.github.munzzyy.jackdaw.engine.Engine
import io.github.munzzyy.jackdaw.engine.real.RealEngine

fun createEngine(context: Context): Engine = RealEngine.shared(context)
