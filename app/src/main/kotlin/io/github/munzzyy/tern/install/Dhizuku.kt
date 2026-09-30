package io.github.munzzyy.tern.install

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import io.github.munzzyy.tern.engine.InstallerReadiness
import java.io.FileDescriptor

/**
 * Tern's side of Dhizuku's protocol (github.com/iamr0s/Dhizuku), written from the protocol and not
 * from Dhizuku's library. Dhizuku holds the device owner role and shares it through a binder: the
 * owner's provider hands its binder over for a client binder, the binder says whether Tern may use
 * it, Dhizuku's own activity asks the person, and a call on a binder from [wrap] is made by Dhizuku
 * instead of Tern. Nothing of it leaves the device.
 */
class Dhizuku(private val context: Context, private val changed: () -> Unit = {}) {
    /** The binder of the Dhizuku in [owner], while it lives. */
    private class Server(val owner: String, val binder: IBinder)

    @Volatile private var server: Server? = null

    /** What Dhizuku is given with a request for its binder. It tells the version of the protocol and nothing else. */
    private val client = object : Binder(DhizukuProtocol.CLIENT) {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != DhizukuProtocol.CLIENT_GET_VERSION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DhizukuProtocol.CLIENT)
            reply?.writeNoException()
            reply?.writeInt(DhizukuProtocol.CLIENT_VERSION)
            return true
        }
    }

    /** How Dhizuku stands with Tern now. Asks Dhizuku, so never on the main thread. */
    fun state(): DhizukuState = facts().state()

    /** The package of the Dhizuku to install through, when Tern may use it now. Otherwise [DhizukuException] says why not. */
    fun ready(): String {
        val facts = facts()
        val state = facts.state()
        return facts.owner?.takeIf { state == DhizukuState.READY } ?: throw DhizukuException(state)
    }

    /** The package Android names as device owner, else as profile owner, as Dhizuku's library finds it. Null when there is neither. */
    fun owner(): String? = try {
        context.getSystemService(DevicePolicyManager::class.java)?.let(::ownerOf)
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Opens Dhizuku's own question to the person. [answered] runs when Dhizuku has their answer,
     * which is then asked of Dhizuku again and never taken from the call. False when there is no
     * owner to ask.
     */
    fun ask(answered: () -> Unit): Boolean {
        val owner = owner() ?: return false
        val listener = object : Binder(DhizukuProtocol.LISTENER) {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != DhizukuProtocol.LISTENER_ON_RESULT) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(DhizukuProtocol.LISTENER)
                answered()
                return true
            }
        }
        val request = Bundle().apply {
            putInt(DhizukuProtocol.EXTRA_UID, Process.myUid())
            putBinder(DhizukuProtocol.EXTRA_LISTENER, listener)
        }
        val intent = Intent(DhizukuProtocol.requestAction(owner))
            .setPackage(owner)
            .putExtras(request)
            // Older versions of Dhizuku read the request from a bundle of this name.
            .putExtra(DhizukuProtocol.EXTRA_REQUEST, request)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** [target] as a binder whose every call Dhizuku makes, as the device owner, instead of Tern. */
    fun wrap(target: IBinder): IBinder = Through(target)

    private fun facts(): DhizukuFacts {
        val owner = owner()
        val granted = owner?.let(::serverOf)?.let(::granted)
        return DhizukuFacts(
            installed = installed(DhizukuProtocol.OFFICIAL),
            owner = owner,
            ownerInstalls = owner != null && installsWithoutAsking(owner),
            answered = granted != null,
            granted = granted == true,
        )
    }

    private fun ownerOf(dpm: DevicePolicyManager): String? {
        val admins = dpm.activeAdmins.orEmpty().map { it.packageName }
        return admins.firstOrNull { dpm.isDeviceOwnerApp(it) } ?: admins.firstOrNull { dpm.isProfileOwnerApp(it) }
    }

    /** Android lets the device owner install without asking, and a profile owner only in a profile affiliated with the device. */
    private fun installsWithoutAsking(owner: String): Boolean = try {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm != null && (dpm.isDeviceOwnerApp(owner) || (dpm.isProfileOwnerApp(owner) && dpm.isAffiliatedUser))
    } catch (_: RuntimeException) {
        false
    }

    private fun installed(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** The binder of the Dhizuku in [owner]: the one kept while it lives, else asked of the owner's own provider. */
    private fun serverOf(owner: String): IBinder? {
        server?.takeIf { it.owner == owner && it.binder.isBinderAlive }?.let { return it.binder }
        val authority = DhizukuProtocol.authority(owner)
        // Only the owner's own provider is asked: an app that took the name first never gets Tern's calls.
        if (providerPackage(authority) != owner) return null
        val extras = Bundle().apply { putBinder(DhizukuProtocol.EXTRA_CLIENT, client) }
        val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).build()
        val binder = try {
            context.contentResolver.call(uri, DhizukuProtocol.METHOD_CLIENT, null, extras)?.getBinder(DhizukuProtocol.EXTRA_SERVER)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val kept = Server(owner, binder)
        try {
            binder.linkToDeath({
                if (server === kept) server = null
                changed()
            }, 0)
        } catch (_: RemoteException) {
            return null
        }
        server = kept
        return binder
    }

    private fun providerPackage(authority: String): String? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveContentProvider(authority, 0)
        }
        return info?.packageName
    }

    /** Whether Dhizuku lets Tern in. Null when the binder did not answer. */
    private fun granted(server: IBinder): Boolean? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DhizukuProtocol.SERVER)
            if (!server.transact(DhizukuProtocol.IS_PERMISSION_GRANTED, data, reply, 0)) return null
            reply.readException()
            reply.readInt() != 0
        } catch (_: RemoteException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** The binder to send a call through: the one kept, else the owner's again, as after Dhizuku restarted. */
    private fun current(): IBinder? = server?.binder?.takeIf { it.isBinderAlive } ?: owner()?.let(::serverOf)

    /** Sends each call to Dhizuku, which makes it on [target] with the rest of the call as it was written. */
    private inner class Through(private val target: IBinder) : IBinder {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val server = current() ?: throw DhizukuException(DhizukuState.NOT_ANSWERING)
            val remote = Parcel.obtain()
            try {
                remote.writeInterfaceToken(DhizukuProtocol.REMOTE)
                remote.writeStrongBinder(target)
                remote.writeInt(code)
                remote.writeInt(flags)
                remote.appendFrom(data, 0, data.dataSize())
                return server.transact(DhizukuProtocol.REMOTE_TRANSACT, remote, reply, 0)
            } catch (e: RemoteException) {
                // Dhizuku went away, so the call was not made.
                throw DhizukuException(DhizukuState.NOT_ANSWERING, e)
            } finally {
                remote.recycle()
            }
        }

        // Never the object itself: a proxy is always made over this binder, so that each call comes through transact.
        override fun queryLocalInterface(descriptor: String): IInterface? = null

        override fun getInterfaceDescriptor(): String? = target.interfaceDescriptor

        override fun pingBinder(): Boolean = target.pingBinder()

        override fun isBinderAlive(): Boolean = target.isBinderAlive

        override fun dump(fd: FileDescriptor, args: Array<out String>?) = target.dump(fd, args)

        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) = target.dumpAsync(fd, args)

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = target.linkToDeath(recipient, flags)

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = target.unlinkToDeath(recipient, flags)
    }
}

/** How Dhizuku stands with Tern, and what the settings say of it. */
enum class DhizukuState(val readiness: InstallerReadiness) {
    /** This Android lacks a part of its installer that Tern reaches through Dhizuku. */
    UNSUPPORTED(InstallerReadiness.DHIZUKU_UNSUPPORTED),
    NOT_INSTALLED(InstallerReadiness.DHIZUKU_NOT_INSTALLED),

    /** Dhizuku is installed, and no Dhizuku is an owner that may install without asking. */
    NOT_OWNER(InstallerReadiness.DHIZUKU_NOT_OWNER),

    /** Dhizuku is the owner and did not hand over its binder, or the binder did not answer. */
    NOT_ANSWERING(InstallerReadiness.DHIZUKU_NOT_ANSWERING),
    NOT_ALLOWED(InstallerReadiness.DHIZUKU_NOT_ALLOWED),
    READY(InstallerReadiness.READY),
}

/** Dhizuku could not be used for what was asked; [state] says why. */
class DhizukuException(val state: DhizukuState, cause: Throwable? = null) : IllegalStateException("Dhizuku: ${state.name}", cause)

/** What Tern found out about Dhizuku, from Android and from Dhizuku itself. */
internal data class DhizukuFacts(
    /** Dhizuku's own package is on the device. */
    val installed: Boolean,
    /** The package Android names as device owner, else as profile owner. */
    val owner: String?,
    /** [owner] may install without asking: it is the device owner, or a profile owner affiliated with the device. */
    val ownerInstalls: Boolean,
    /** [owner]'s provider handed over a binder, and the binder answered. */
    val answered: Boolean,
    /** The binder says Tern may use it. */
    val granted: Boolean,
) {
    fun state(): DhizukuState = when {
        // An owner that does not answer is another app, unless it is Dhizuku itself.
        !answered -> when {
            owner == DhizukuProtocol.OFFICIAL -> DhizukuState.NOT_ANSWERING
            installed -> DhizukuState.NOT_OWNER
            else -> DhizukuState.NOT_INSTALLED
        }
        !ownerInstalls -> DhizukuState.NOT_OWNER
        !granted -> DhizukuState.NOT_ALLOWED
        else -> DhizukuState.READY
    }
}

/** The names and numbers of Dhizuku's protocol, as its server and its library define them. */
internal object DhizukuProtocol {
    /** The package of Dhizuku's own builds. An app that speaks the protocol under another name uses the other names below. */
    const val OFFICIAL = "com.rosan.dhizuku"

    const val METHOD_CLIENT = "client"
    const val EXTRA_CLIENT = "client"
    const val EXTRA_SERVER = "dhizuku_binder"
    const val EXTRA_UID = "uid"
    const val EXTRA_LISTENER = "request_permission_binder"
    const val EXTRA_REQUEST = "bundle"

    const val SERVER = "com.rosan.dhizuku.aidl.IDhizuku"
    const val CLIENT = "com.rosan.dhizuku.aidl.IDhizukuClient"
    const val LISTENER = "com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener"

    /** What a call to be made on another binder is sent under. */
    const val REMOTE = "com.rosan.dhizuku.server"

    /** Each call is the first call plus the number its interface gives it. */
    const val IS_PERMISSION_GRANTED = IBinder.FIRST_CALL_TRANSACTION + 2
    const val REMOTE_TRANSACT = IBinder.FIRST_CALL_TRANSACTION + 10
    const val CLIENT_GET_VERSION = IBinder.FIRST_CALL_TRANSACTION
    const val LISTENER_ON_RESULT = IBinder.FIRST_CALL_TRANSACTION
    const val CLIENT_VERSION = 1

    fun authority(owner: String): String = if (owner == OFFICIAL) "$OFFICIAL.server.provider" else "$owner.dhizuku_server.provider"

    fun requestAction(owner: String): String =
        if (owner == OFFICIAL) "$OFFICIAL.action.request.permission" else "$owner.action.REQUEST_DHIZUKU_PERMISSION"
}
