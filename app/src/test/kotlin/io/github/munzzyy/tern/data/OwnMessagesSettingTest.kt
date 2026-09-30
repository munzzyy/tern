package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonBool
import io.github.munzzyy.tern.engine.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeping Tern's own messages in the log is off until the person turns it on, and travels with the other settings. */
class OwnMessagesSettingTest {
    @Test
    fun itIsOffUntilTurnedOn() {
        assertFalse(Settings().keepOwnMessages)
    }

    @Test
    fun anExportCarriesItAndAnImportBringsItBack() {
        val on = Settings().copy(keepOwnMessages = true)
        val written = SettingsJson.encode(on)
        assertEquals(JsonBool(true), written["keepOwnMessages"])
        assertEquals(JsonBool(false), SettingsJson.encode(Settings())["keepOwnMessages"])
        val back = SettingsJson.apply(Json.parseObject(Json.write(written)), Settings())
        assertTrue(back.keepOwnMessages)
        assertEquals(on, back)
        assertTrue("keepOwnMessages" in SettingsJson.KEYS)
    }

    @Test
    fun aFileCanTurnItOffAgain() {
        val back = SettingsJson.apply(Json.parseObject("""{"keepOwnMessages":false}"""), Settings().copy(keepOwnMessages = true))
        assertFalse(back.keepOwnMessages)
    }

    @Test
    fun aValueThatIsNotYesOrNoChangesNothing() {
        for (value in listOf("\"yes\"", "1", "null", "{}")) {
            val file = Json.parseObject("""{"keepOwnMessages":$value}""")
            assertFalse(value, SettingsJson.apply(file, Settings()).keepOwnMessages)
            assertTrue(value, SettingsJson.apply(file, Settings().copy(keepOwnMessages = true)).keepOwnMessages)
        }
    }
}
