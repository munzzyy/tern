package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.engine.InstallerChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallerChoicesTest {
    private val single = InstallerChoice("org.example.single", "Single Installer", "org.example.single.Install", "Single Installer")
    private val viewWay = InstallerChoice("org.example.many", "Many Ways", "org.example.many.ViewActivity", "Install")
    private val packageWay = InstallerChoice("org.example.many", "Many Ways", "org.example.many.PackageActivity", "Add as a package")
    private val another = InstallerChoice("org.example.another", "another installer", "org.example.another.Main", "another installer")

    @Test
    fun anAppWithOneWayInLeavesTheWayToTheApp() {
        val choices = InstallerChoices.of(listOf(single))
        assertEquals(listOf(InstallerChoice("org.example.single", "Single Installer")), choices)
    }

    @Test
    fun anAppWithSeveralWaysInListsEachByItsNameAndAppsComeByTheirs() {
        // The same way found by both questions, to open the file and to install a package, is one choice.
        val choices = InstallerChoices.of(listOf(single, viewWay, packageWay, viewWay, another))
        assertEquals(
            listOf("org.example.another" to null, "org.example.many" to "org.example.many.PackageActivity", "org.example.many" to "org.example.many.ViewActivity", "org.example.single" to null),
            choices.map { it.packageName to it.activity },
        )
    }

    @Test
    fun theSettingsPickTheWayTheyNameElseTheApp() {
        val choices = InstallerChoices.of(listOf(single, viewWay, packageWay))
        assertEquals(packageWay, InstallerChoices.picked(choices, "org.example.many", "org.example.many.PackageActivity"))
        assertEquals("org.example.many", InstallerChoices.picked(choices, "org.example.many", "org.example.many.Gone")?.packageName)
        assertEquals(InstallerChoice("org.example.single", "Single Installer"), InstallerChoices.picked(choices, "org.example.single", null))
        assertNull(InstallerChoices.picked(choices, "org.example.gone", null))
        assertNull(InstallerChoices.picked(choices, null, null))
    }
}
