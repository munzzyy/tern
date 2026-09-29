package io.github.munzzyy.tern.enginetest

import android.os.Build
import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the shipped network security configuration makes Android enforce, asked of Android itself. */
@RunWith(AndroidJUnit4::class)
class CertificateTransparencyTest {
    private val policy = NetworkSecurityPolicy.getInstance()

    @Test
    fun aCertificateThatIsInNoPublicLogIsRefusedWhereAndroidCanCheck() {
        assumeTrue("Android ${Build.VERSION.RELEASE} cannot check certificate transparency for an app", Build.VERSION.SDK_INT >= 36)
        for (host in listOf("github.com", "codeberg.org", "f-droid.org", "example.org")) {
            assertTrue("no certificate transparency for $host", policy.isCertificateTransparencyVerificationRequired(host))
        }
    }

    @Test
    fun plainHttpIsRefusedForEveryHost() {
        assertFalse(policy.isCleartextTrafficPermitted)
        for (host in listOf("github.com", "localhost", "192.168.1.20", "example.org")) {
            assertFalse("plain HTTP is allowed for $host", policy.isCleartextTrafficPermitted(host))
        }
    }
}
