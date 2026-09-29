package io.github.munzzyy.stamp.core.apk

import java.io.IOException

/** The file is not a well-formed APK, zip or bundle, or it breaks one of the inspector's size caps. */
open class ApkFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The server answered a range request with the whole file, so remote inspection is impossible. */
class RangeNotSupportedException(val url: String) : IOException("Server ignored the Range header for $url")

/** The remote file changed between two range requests, so the pieces cannot be combined. */
class RemoteFileChangedException(val url: String, detail: String) : IOException("Remote file changed during inspection of $url: $detail")

/** Inspecting would have transferred more than [budgetBytes]. */
class InspectionBudgetException(val budgetBytes: Long) : IOException("Inspection would transfer more than $budgetBytes bytes")

/** The bundle holds nothing this device can run, for example no split for any of its ABIs. */
class IncompatibleDeviceException(message: String) : IOException(message)
