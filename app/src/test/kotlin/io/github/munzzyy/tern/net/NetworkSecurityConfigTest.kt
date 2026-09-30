package io.github.munzzyy.tern.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tern trusts the certificate authorities Android trusts and no other, for every host, and never
 * turns Certificate Transparency off. A store that needs more is not read.
 */
class NetworkSecurityConfigTest {
    private val res = File("src/main/res")
    private val configs = listOf("xml/network_security_config.xml", "xml-v36/network_security_config.xml").map { File(res, it) }

    private fun elements(file: File, tag: String): List<Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun onlyTheSystemsAuthoritiesAreTrusted() {
        for (config in configs) {
            assertTrue("$config exists", config.isFile)
            val sources = elements(config, "certificates").map { it.getAttribute("src") }
            assertEquals("$config", listOf("system"), sources)
            assertEquals("$config names no host of its own", emptyList<Element>(), elements(config, "domain-config"))
        }
    }

    @Test
    fun certificateTransparencyIsNeverTurnedOff() {
        for (config in configs) {
            assertTrue("$config", elements(config, "certificateTransparency").none { it.getAttribute("enabled") == "false" })
        }
        assertEquals(listOf("true"), elements(configs[1], "certificateTransparency").map { it.getAttribute("enabled") })
    }

    @Test
    fun noCertificateShipsInTheApp() {
        val found = res.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("pem", "crt", "cer", "der", "p12", "pfx", "bks") }.toList()
        assertEquals(emptyList<File>(), found)
    }
}
