package com.example.systemhealth

/** Automatic scope includes future apps without a saved package-name list. */
internal object AppCapturePolicy {
    private val packageId = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    fun isEligible(ownPackage: String, sourcePackage: String): Boolean =
        sourcePackage != ownPackage && sourcePackage != "android" &&
            (!sourcePackage.startsWith("com.android.") || sourcePackage == "com.android.chrome") &&
            packageId.matches(sourcePackage)

    fun includes(
        ownPackage: String,
        sourcePackage: String,
        allApps: Boolean,
        selectedPackages: Set<String>
    ): Boolean = isEligible(ownPackage, sourcePackage) &&
        (allApps || sourcePackage in selectedPackages)
}
