package io.github.munzzyy.stamp

import android.content.Context
import io.github.munzzyy.stamp.engine.Engine
import io.github.munzzyy.stamp.engine.real.RealEngine

fun createEngine(context: Context): Engine = RealEngine.shared(context)
