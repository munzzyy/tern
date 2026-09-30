package io.github.munzzyy.tern.install

import android.annotation.SuppressLint
import android.content.pm.PackageInstaller
import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import io.github.munzzyy.tern.log.TernLog
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * The parts of Android outside its SDK that installing through Dhizuku takes, as Obtainium's
 * installer plugin takes them: the package service and its installer as binders, a session of it
 * as a binder, and the constructors of PackageInstaller and its Session that take such binders.
 */
internal object NonSdk {
    const val SERVICE_MANAGER = "android.os.ServiceManager"
    const val PACKAGE_MANAGER = "android.content.pm.IPackageManager"
    const val PACKAGE_MANAGER_STUB = "$PACKAGE_MANAGER\$Stub"
    const val PACKAGE_INSTALLER = "android.content.pm.IPackageInstaller"
    const val PACKAGE_INSTALLER_STUB = "$PACKAGE_INSTALLER\$Stub"

    /** Only a type here: no member of it is looked up. */
    const val SESSION = "android.content.pm.IPackageInstallerSession"
    const val SESSION_STUB = "$SESSION\$Stub"
    const val INSTALLER = "android.content.pm.PackageInstaller"
    const val INSTALLER_SESSION = "$INSTALLER\$Session"

    /** Every class a member is looked up in, and so every class exempted. */
    val CLASSES: List<String> = listOf(
        SERVICE_MANAGER, PACKAGE_MANAGER, PACKAGE_MANAGER_STUB, PACKAGE_INSTALLER, PACKAGE_INSTALLER_STUB, SESSION_STUB, INSTALLER, INSTALLER_SESSION,
    )

    /**
     * The signature prefixes exempted from Android's restrictions on non-SDK interfaces: each of
     * [CLASSES] as ART writes it, closed by its semicolon, so that no other class shares a prefix,
     * not even one nested in it.
     */
    val EXEMPTIONS: List<String> = CLASSES.map { "L" + it.replace('.', '/') + ";" }
}

/**
 * The members of [NonSdk], found once per process after its classes were exempted. A call that
 * a member refuses comes out as what the member threw; one that reflection refuses comes out as
 * [DhizukuException] with [DhizukuState.UNSUPPORTED].
 */
internal class NonSdkCalls private constructor(
    private val getService: Method,
    private val packageManagerOf: Method,
    private val getPackageInstaller: Method,
    private val installerOf: Method,
    private val openSession: Method,
    private val sessionOf: Method,
    private val newInstaller: Constructor<*>,
    private val newSession: Constructor<*>,
) {
    /** Android's installer service, as a proxy whose every call [wrap] hands to Dhizuku. The package service is asked for it through Dhizuku too. */
    fun installer(wrap: (IBinder) -> IBinder): Any = reflected {
        val service = getService.invoke(null, PACKAGE_SERVICE) as? IBinder ?: throw DhizukuException(DhizukuState.UNSUPPORTED)
        val packageManager = packageManagerOf.invoke(null, wrap(service))
        val installer = getPackageInstaller.invoke(packageManager) as IInterface
        installerOf.invoke(null, wrap(installer.asBinder())) ?: throw DhizukuException(DhizukuState.UNSUPPORTED)
    }

    /** A PackageInstaller over [installer] whose sessions name [installerPackage] as their installer, for the user [userId]. */
    fun packageInstaller(installer: Any, installerPackage: String, userId: Int): PackageInstaller = reflected {
        // Android 12 added an attribution tag, which is null for a package as a whole.
        val args = if (newInstaller.parameterTypes.size == 4) arrayOf(installer, installerPackage, null, userId) else arrayOf(installer, installerPackage, userId)
        newInstaller.newInstance(*args) as PackageInstaller
    }

    /** Session [sessionId], opened through [installer], as a PackageInstaller.Session whose calls [wrap] hands to Dhizuku as well. */
    fun session(installer: Any, sessionId: Int, wrap: (IBinder) -> IBinder): PackageInstaller.Session = reflected {
        val session = openSession.invoke(installer, sessionId) as IInterface
        newSession.newInstance(sessionOf.invoke(null, wrap(session.asBinder()))) as PackageInstaller.Session
    }

    /** Runs a reflected call, and hands on what the member threw as it threw it. */
    private inline fun <T> reflected(block: () -> T): T = try {
        block()
    } catch (e: ReflectiveOperationException) {
        throw unwrapped(e)
    }

    companion object {
        private const val TAG = "TernNonSdk"
        private const val PACKAGE_SERVICE = "package"

        /** A reflected call that failed with [e]: what the member threw, or [DhizukuState.UNSUPPORTED] when reflection itself refused. */
        internal fun unwrapped(e: ReflectiveOperationException): RuntimeException = when (val thrown = (e as? InvocationTargetException)?.targetException) {
            is RuntimeException -> thrown
            is RemoteException -> DhizukuException(DhizukuState.NOT_ANSWERING, thrown)
            else -> DhizukuException(DhizukuState.UNSUPPORTED, e)
        }

        private val found: NonSdkCalls? by lazy(::find)

        /** False on an Android that lacks one of the members. */
        val available: Boolean get() = found != null

        /** The members, or [DhizukuException] with [DhizukuState.UNSUPPORTED] on an Android that lacks one. */
        fun get(): NonSdkCalls = found ?: throw DhizukuException(DhizukuState.UNSUPPORTED)

        // Android's own installer classes that Dhizuku needs; the first line exempts each of them by name.
        @SuppressLint("PrivateApi")
        private fun find(): NonSdkCalls? = try {
            if (!HiddenApiBypass.addHiddenApiExemptions(*NonSdk.EXEMPTIONS.toTypedArray())) TernLog.w(TAG, "Android did not take the exemptions")
            val binder = IBinder::class.java
            val installer = Class.forName(NonSdk.PACKAGE_INSTALLER)
            NonSdkCalls(
                getService = Class.forName(NonSdk.SERVICE_MANAGER).getMethod("getService", String::class.java),
                packageManagerOf = Class.forName(NonSdk.PACKAGE_MANAGER_STUB).getMethod("asInterface", binder),
                getPackageInstaller = Class.forName(NonSdk.PACKAGE_MANAGER).getMethod("getPackageInstaller"),
                installerOf = Class.forName(NonSdk.PACKAGE_INSTALLER_STUB).getMethod("asInterface", binder),
                openSession = installer.getMethod("openSession", Int::class.java),
                sessionOf = Class.forName(NonSdk.SESSION_STUB).getMethod("asInterface", binder),
                newInstaller = installerConstructor(installer),
                newSession = PackageInstaller.Session::class.java.getConstructor(Class.forName(NonSdk.SESSION)),
            )
        } catch (e: ReflectiveOperationException) {
            TernLog.w(TAG, "Installing through Dhizuku is not possible on this Android: $e")
            null
        } catch (e: LinkageError) {
            TernLog.w(TAG, "Installing through Dhizuku is not possible on this Android: $e")
            null
        } catch (e: RuntimeException) {
            TernLog.w(TAG, "Installing through Dhizuku is not possible on this Android: $e")
            null
        }

        /** The constructor with an attribution tag, from Android 12 on, else the one before it. */
        private fun installerConstructor(installer: Class<*>): Constructor<*> = try {
            PackageInstaller::class.java.getConstructor(installer, String::class.java, String::class.java, Int::class.java)
        } catch (_: NoSuchMethodException) {
            PackageInstaller::class.java.getConstructor(installer, String::class.java, Int::class.java)
        }
    }
}
