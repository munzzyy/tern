package io.github.munzzyy.jackdaw.core.model

data class DeviceProfile(
    /** Most preferred first, as Build.SUPPORTED_ABIS reports them. */
    val abis: List<String>,
    val sdk: Int,
    val densityDpi: Int,
    /** BCP 47 language subtags, most preferred first. */
    val languages: List<String> = listOf("en"),
    val television: Boolean = false,
    val watch: Boolean = false,
    val automotive: Boolean = false,
) {
    companion object {
        val ARM64_PHONE = DeviceProfile(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), sdk = 36, densityDpi = 420)
        val X86_64_EMULATOR = DeviceProfile(listOf("x86_64", "arm64-v8a"), sdk = 36, densityDpi = 420)
    }
}
