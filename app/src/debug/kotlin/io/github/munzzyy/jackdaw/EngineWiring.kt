package io.github.munzzyy.jackdaw

import android.content.Context
import io.github.munzzyy.jackdaw.engine.Engine
import io.github.munzzyy.jackdaw.fake.FakeEngine

fun createEngine(context: Context): Engine = FakeEngine(context.applicationContext)
