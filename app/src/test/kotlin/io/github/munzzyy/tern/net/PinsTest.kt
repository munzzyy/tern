package io.github.munzzyy.tern.net

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinsTest {
    private fun root(name: String): X509Certificate =
        javaClass.getResourceAsStream("/pins/$name.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    private val isrg = listOf("isrg-root-x1", "isrg-root-x2", "isrg-root-ye", "isrg-root-yr").map(::root)
    private val sectigo = listOf("sectigo-pub-serv-auth-r46", "sectigo-pub-serv-auth-e46").map(::root)
    private val other = root("harica-tls-root-2021-rsa")

    @Test
    fun everyPinIsTheKeyOfTheRootItNames() {
        assertEquals("C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", Pins.keyOf(isrg[0]))
        assertEquals(isrg.map(Pins::keyOf).toSet(), Pins.forHost("codeberg.org"))
        assertEquals(sectigo.map(Pins::keyOf).toSet(), Pins.forHost("gitlab.com"))
        assertEquals((sectigo + isrg).map(Pins::keyOf).toSet(), Pins.forHost("github.com"))
    }

    @Test
    fun theForgesAndTheirSubdomainsArePinnedAndNothingElse() {
        assertEquals(Pins.forHost("github.com"), Pins.forHost("api.github.com"))
        assertEquals(Pins.forHost("github.com"), Pins.forHost("release-assets.githubusercontent.com"))
        assertEquals(Pins.forHost("github.com"), Pins.forHost("GITHUB.COM."))
        assertNull(Pins.forHost("raw.githubusercontent.com"))
        assertNull(Pins.forHost("notgithub.com"))
        assertNull(Pins.forHost("github.com.example.org"))
        assertNull(Pins.forHost("f-droid.org"))
    }

    @Test
    fun aChainIsTakenOnlyWhenItReachesAPinnedRoot() {
        assertTrue(Pins.allows("codeberg.org", listOf(isrg[1])))
        assertFalse(Pins.allows("codeberg.org", listOf(sectigo[0])))
        assertFalse(Pins.allows("gitlab.com", listOf(other)))
        assertTrue(Pins.allows("f-droid.org", listOf(other)))
        assertTrue(Pins.allows(null, emptyList()))
        assertFalse(Pins.allows("api.github.com", emptyList()))
    }
}
