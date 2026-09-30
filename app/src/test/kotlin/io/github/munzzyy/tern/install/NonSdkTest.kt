package io.github.munzzyy.tern.install

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Tern opens of Android beyond its SDK to install through Dhizuku: the classes it looks into, and nothing more. */
class NonSdkTest {
    private fun exempt(signature: String): Boolean = NonSdk.EXEMPTIONS.any { signature.startsWith(it) }

    @Test
    fun onlyTheClassesTernLooksIntoAreExempted() {
        assertEquals(
            listOf(
                "Landroid/os/ServiceManager;",
                "Landroid/content/pm/IPackageManager;",
                "Landroid/content/pm/IPackageManager\$Stub;",
                "Landroid/content/pm/IPackageInstaller;",
                "Landroid/content/pm/IPackageInstaller\$Stub;",
                "Landroid/content/pm/IPackageInstallerSession\$Stub;",
                "Landroid/content/pm/PackageInstaller;",
                "Landroid/content/pm/PackageInstaller\$Session;",
            ),
            NonSdk.EXEMPTIONS,
        )
        for (prefix in NonSdk.EXEMPTIONS) {
            assertTrue(prefix, prefix.startsWith("Landroid/") && prefix.endsWith(";"))
            assertEquals(prefix, 1, prefix.count { it == ';' })
        }
    }

    @Test
    fun everyMemberTernReachesIsCovered() {
        listOf(
            "Landroid/os/ServiceManager;->getService(Ljava/lang/String;)Landroid/os/IBinder;",
            "Landroid/content/pm/IPackageManager\$Stub;->asInterface(Landroid/os/IBinder;)Landroid/content/pm/IPackageManager;",
            "Landroid/content/pm/IPackageManager;->getPackageInstaller()Landroid/content/pm/IPackageInstaller;",
            "Landroid/content/pm/IPackageInstaller\$Stub;->asInterface(Landroid/os/IBinder;)Landroid/content/pm/IPackageInstaller;",
            "Landroid/content/pm/IPackageInstaller;->openSession(I)Landroid/content/pm/IPackageInstallerSession;",
            "Landroid/content/pm/IPackageInstallerSession\$Stub;->asInterface(Landroid/os/IBinder;)Landroid/content/pm/IPackageInstallerSession;",
            "Landroid/content/pm/PackageInstaller;-><init>(Landroid/content/pm/IPackageInstaller;Ljava/lang/String;Ljava/lang/String;I)V",
            "Landroid/content/pm/PackageInstaller;-><init>(Landroid/content/pm/IPackageInstaller;Ljava/lang/String;I)V",
            "Landroid/content/pm/PackageInstaller\$Session;-><init>(Landroid/content/pm/IPackageInstallerSession;)V",
        ).forEach { assertTrue(it, exempt(it)) }
    }

    @Test
    fun nothingElseOfAndroidIsOpened() {
        listOf(
            "Landroid/app/ActivityThread;->currentActivityThread()Landroid/app/ActivityThread;",
            "Landroid/os/ServiceManagerNative;->asInterface(Landroid/os/IBinder;)Landroid/os/IServiceManager;",
            "Landroid/content/pm/IPackageManagerNative\$Stub;->asInterface(Landroid/os/IBinder;)Landroid/content/pm/IPackageManagerNative;",
            "Landroid/content/pm/IPackageManager\$Stub\$Proxy;->mRemote:Landroid/os/IBinder;",
            "Landroid/content/pm/IPackageInstallerSession;->commit(Landroid/content/IntentSender;Z)V",
            "Landroid/content/pm/PackageInstaller\$SessionParams;->installFlags:I",
            "Landroid/content/pm/PackageManager;->INSTALL_ALLOW_TEST:I",
            "Landroid/app/admin/IDevicePolicyManager\$Stub;->asInterface(Landroid/os/IBinder;)Landroid/app/admin/IDevicePolicyManager;",
        ).forEach { assertFalse(it, exempt(it)) }
    }

    @Test
    fun theNamesAreThoseOfAndroidsOwnClasses() {
        assertEquals(PackageInstaller::class.java.name, NonSdk.INSTALLER)
        assertEquals(PackageInstaller.Session::class.java.name, NonSdk.INSTALLER_SESSION)
        // The session's interface is only a type to Tern; no member of it is looked up.
        assertFalse(NonSdk.SESSION in NonSdk.CLASSES)
        assertTrue(NonSdk.SESSION_STUB in NonSdk.CLASSES)
    }
}
