package io.github.munzzyy.tern.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObbNamesTest {
    @Test
    fun anObbFileKeepsTheLastPartOfItsPath() {
        assertEquals("main.12.org.example.app.obb", ObbNames.of("Android/obb/org.example.app/main.12.org.example.app.obb"))
        assertEquals("patch.3.org.example.app.obb", ObbNames.of("patch.3.org.example.app.obb"))
        assertEquals("data_v2+extra-1.OBB", ObbNames.of("data_v2+extra-1.OBB"))
    }

    @Test
    fun aNameThatCouldMeanMoreThanAFileIsRefused() {
        for (name in listOf("a b.obb", "a'b.obb", "a\"b.obb", "\$(id).obb", "a;b.obb", "a`b.obb", "a\\b.obb", ".hidden.obb", "nä.obb", "a\nb.obb", "a*b.obb")) {
            assertNull(name, ObbNames.of(name))
        }
        assertNull(ObbNames.of("x".repeat(252) + ".obb"))
        assertNull(ObbNames.of("readme.txt"))
    }
}
