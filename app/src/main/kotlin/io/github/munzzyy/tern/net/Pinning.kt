package io.github.munzzyy.tern.net

import android.net.http.X509TrustManagerExtensions
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Obtainium's certificate pinning, for the forges it pins. With the setting on, a connection to one
 * of them is accepted only when the chain Android verified runs through one of the roots named for
 * it, so a certificate some other authority was made to issue is refused. Android's own checks all
 * run first; a pin adds a condition and never lifts one.
 */
object Pins {
    private val SECTIGO_46 = setOf(
        "Douxi77vs4G+Ib/BogbTFymEYq0QSFXwSgVCaZcI09Q=", // Sectigo Public Server Authentication Root R46
        "sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=", // Sectigo Public Server Authentication Root E46
    )
    private val ISRG = setOf(
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // ISRG Root X1
        "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // ISRG Root X2
        "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", // ISRG Root YE
        "fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=", // ISRG Root YR
    )

    /** Each forge, and the hosts GitHub's release files come from, with the root keys they are pinned to. */
    private val BY_DOMAIN: Map<String, Set<String>> = mapOf(
        "github.com" to SECTIGO_46 + ISRG,
        "release-assets.githubusercontent.com" to SECTIGO_46 + ISRG,
        "objects.githubusercontent.com" to SECTIGO_46 + ISRG,
        "codeberg.org" to ISRG,
        "gitlab.com" to SECTIGO_46,
    )

    /** The root keys a connection to [host] must reach, or null where nothing is pinned. */
    fun forHost(host: String): Set<String>? {
        val name = host.lowercase().trimEnd('.')
        return BY_DOMAIN.entries.firstOrNull { name == it.key || name.endsWith("." + it.key) }?.value
    }

    /** The SHA-256 of a certificate's public key, in base64, as pins are written. */
    fun keyOf(certificate: X509Certificate): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded))

    /** Whether the chain Android verified for [host] may be used: yes where nothing is pinned, or where it reaches a pinned key. */
    fun allows(host: String?, verified: List<X509Certificate>): Boolean {
        val pins = host?.let(::forHost) ?: return true
        return verified.any { keyOf(it) in pins }
    }
}

/** Whether connections are pinned, as the settings say once the engine knows them. */
class PinChoice {
    @Volatile
    private var ask: () -> Boolean = { false }

    fun follow(ask: () -> Boolean) {
        this.ask = ask
    }

    fun on(): Boolean = ask()
}

/** Android's own trust manager with [Pins] on top. */
internal class PinningTrustManager(private val platform: X509ExtendedTrustManager) : X509ExtendedTrustManager() {
    private val extensions = X509TrustManagerExtensions(platform)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
        platform.checkServerTrusted(chain, authType, socket)
        pinned(chain, authType, (socket as? SSLSocket)?.handshakeSession?.peerHost)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
        platform.checkServerTrusted(chain, authType, engine)
        pinned(chain, authType, engine.handshakeSession?.peerHost ?: engine.peerHost)
    }

    // Without a host there is nothing to pin by, and Android's check alone applies.
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = platform.checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = platform.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = platform.checkClientTrusted(chain, authType, engine)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = platform.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    private fun pinned(chain: Array<X509Certificate>, authType: String, host: String?) {
        if (host == null || Pins.forHost(host) == null) return
        val verified = extensions.checkServerTrusted(chain, authType, host)
        if (!Pins.allows(host, verified)) throw CertificateException("The certificate of $host does not lead to a root Tern pins for it")
    }

    companion object {
        /** Sockets that check as Android does and then the pins; made once, when pinning is first asked for. */
        val sockets: SSLSocketFactory by lazy {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val platform = factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinningTrustManager(platform)), null) }.socketFactory
        }
    }
}
