# Release shrinking is enabled in build.gradle.kts alongside the SDK's default
# proguard-android-optimize.txt. This file is what is left to add on top of that.

# Line numbers, not names: a crash from a shrunken build is only readable if the stack trace still
# carries them. Without SourceFile/LineNumberTable the trace has class and method names to feed to
# retrace but no line, so "somewhere in CoreService" is as good as it gets. Renaming the source
# attribute keeps the trace short while mapping.txt still resolves it.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# R8 writes the deobfuscation map to app/build/outputs/mapping/release/mapping.txt on its own, so no
# -printmapping here: that only duplicates the file into the source tree, where it would then have to
# be kept in step with a build that is not in the tree.

# There is deliberately no -keep for "model" or data classes.
#
# The usual reason to add them is a JSON library that derives wire field names from Kotlin property
# names (Gson, Moshi, kotlinx.serialization). This app has none of those: every request body is built
# by hand with org.json, supplied by the Android platform, and every key on the wire is a string
# literal — .put("device_id", …), .put("battery_percent", …). R8 renames identifiers; it never edits
# string literals. So shrinking a property called lat cannot change the field name lat, and a blanket
# -keep would only make the APK bigger while implying a coupling that is not there.
#
# Nothing is reached by name either: no Class.forName, no kotlin.reflect, no declaredFields. The only
# ::class.java uses are getSystemService(X::class.java), Intent(context, Service::class.java) and
# ComponentName(context, AccessibilityService::class.java) — all direct references R8 can see, and
# every activity, service and receiver named in AndroidManifest.xml is kept by the SDK's default rules
# plus the rules AGP generates from the manifest.
#
# ProximityStateStore was suggested as a keep candidate because it is created lazily. Lazy is a
# Kotlin delegate that compiles to a normal call to its constructor inside the class that declares it;
# ProximityWatch and LocationTracker both call it directly, so the constructor is reachable and needs
# no rule. A -keep for an interface and its two implementations here would change nothing except the
# size of the APK — and a rule that does nothing is worse than none, because the next reader assumes
# there is a reflection risk they must preserve.
#
# What these rules cannot prove is the thing that actually matters: that every payload still parses
# on the backend after shrinking. That is D3 / S1 in docs/SILENT_VERIFICATION.md — one upload of each
# tool from the release APK, checked in the dashboard. A -keep list is not a substitute for it, and a
# debug build never shows the failure.
