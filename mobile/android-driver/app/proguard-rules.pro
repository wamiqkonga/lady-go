# Moshi's KotlinJsonAdapterFactory (Serializer.kt in the generated client) is reflection-based, not
# codegen - it reads constructor parameter names/nullability via kotlin-reflect at runtime. Without
# these rules R8 can rename/strip the generated model classes' fields and constructors, breaking
# every API response's deserialization silently in a release build (security review finding,
# confirmed the release variant otherwise compiles but Moshi parsing of e.g. the OTP-verify
# tokens/the onboarding profile would be the first thing to break at runtime, not compile time).
-keep,allowobfuscation,allowshrinking class kotlin.Metadata
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-dontwarn org.jetbrains.annotations.**
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier interface *

# The generated client is pure data classes (request/response bodies) with no behaviour worth
# shrinking or obfuscating - keeping them whole is simpler and safer than chasing individual
# reflection-sensitive members as new endpoints are added in later slices.
-keep class com.ladygo.driver.api.generated.models.** { *; }
-keep class com.ladygo.driver.api.generated.apis.** { *; }

# androidx.security:security-crypto pulls in Google Tink, whose annotations reference
# com.google.errorprone.annotations.* - a compile-time-only (javac-plugin) dependency that is never
# actually invoked at runtime. R8 otherwise fails the whole release build over these ("Missing
# classes detected" - confirmed by actually running :app:assembleRelease). This is Tink's own
# documented R8/ProGuard caveat, not something specific to this app.
-dontwarn com.google.errorprone.annotations.**
