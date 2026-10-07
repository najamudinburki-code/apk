# Release shrinking is enabled in build.gradle.kts. The phone talks to the backend with
# HttpURLConnection and org.json, both provided by Android, so nothing is kept reflectively.
# Play Services contributes its own consumer rules.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# A missing class here is a runtime crash the debug build never shows. R8 already writes the
# deobfuscation map to build/outputs/mapping/release/mapping.txt, so no -printmapping is needed;
# adding one only duplicates that file into the source tree.
