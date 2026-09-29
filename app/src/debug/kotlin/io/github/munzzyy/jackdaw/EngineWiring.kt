package io.github.munzzyy.jackdaw

import android.content.Context
import io.github.munzzyy.jackdaw.engine.Engine
import io.github.munzzyy.jackdaw.engine.real.RealEngine
import io.github.munzzyy.jackdaw.fake.FakeEngine
import java.io.File

/**
 * Debug builds run the real engine. The stand-in, with its invented apps, takes over under the
 * screen tests and when asked for by hand:
 * adb shell run-as io.github.munzzyy.jackdaw.debug touch files/use-stand-in
 */
fun createEngine(context: Context): Engine {
    val app = context.applicationContext
    return if (underTest() || File(app.filesDir, STAND_IN_FLAG).exists()) FakeEngine(app) else RealEngine.shared(app)
}

private const val STAND_IN_FLAG = "use-stand-in"

private fun underTest(): Boolean = try {
    Class.forName("androidx.test.platform.app.InstrumentationRegistry")
    true
} catch (_: ClassNotFoundException) {
    false
}
