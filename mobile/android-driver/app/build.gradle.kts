import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

// The openapi-generator-gradle-plugin jar registers its Gradle plugin ID as "org.openapi.generator"
// (not "org.openapitools.openapi-generator", despite the Maven coordinate) and isn't published on the
// Gradle Plugin Portal under any marker - confirmed by inspecting META-INF/gradle-plugins/ inside the
// downloaded jar - so it's applied via buildscript classpath + apply(plugin = ...), not the plugins{} DSL.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.openapitools:openapi-generator-gradle-plugin:${libs.versions.openapiGenerator.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

apply(plugin = "org.openapi.generator")

android {
    namespace = "com.ladygo.driver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ladygo.driver"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Android emulator's alias for the host machine running the backend / the
            // driver-registration website's Vite dev server (frontend/driver-registration, port 5175).
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080\"")
            buildConfigField("String", "DRIVER_REGISTRATION_URL", "\"http://10.0.2.2:5175\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val apiBaseUrl = (project.findProperty("LADYGO_API_BASE_URL") as String?) ?: "https://api.invalid"
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            // No real production domain exists yet for this pilot (open question for the product
            // owner, not an engineering default) - never commit a guessed one.
            val driverRegistrationUrl =
                (project.findProperty("LADYGO_DRIVER_REGISTRATION_URL") as String?) ?: "https://driver.invalid"
            buildConfigField("String", "DRIVER_REGISTRATION_URL", "\"$driverRegistrationUrl\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes.add("META-INF/*.version")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        checkReleaseBuilds = true
        abortOnError = true
        warningsAsErrors = false
        // Security-relevant checks are promoted to build-breaking errors even though they are
        // "warning" severity by default - a release build must not silently ship past these.
        fatal.addAll(
            listOf(
                "UnsafeImplicitIntentLaunch",
                "ExportedReceiver",
                "ExportedService",
                "ExportedContentProvider",
                "TrustAllX509TrustManager",
                "BadHostnameVerifier",
                "SetJavaScriptEnabled",
                "WorldWriteableFiles",
                "WorldReadableFiles",
                "SSLCertificateSocketFactoryGetInsecure",
                "LogConditional",
            ),
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The generated API client is committed to the repo (openapi-client/src/...), mirroring how the
// web apps commit their generated client and drift-check it in CI - NOT the more common Android
// pattern of regenerating silently into build/ on every build, because that pattern makes "drift"
// structurally undetectable (nothing to diff against) and this codebase's established convention
// (frontend/*/scripts/check-openapi-types.mjs) is "commit the generated output, fail CI if it's
// stale relative to docs/api/openapi.json".
val openApiSpec = rootProject.projectDir.resolve("../../docs/api/openapi.json")
val generatedClientDir = project.projectDir.resolve("openapi-client")
val generatedClientSrc = generatedClientDir.resolve("src/main/kotlin")
val freshClientDir =
    layout.buildDirectory
        .dir("tmp/openapi-fresh")
        .get()
        .asFile

fun GenerateTask.configureLadyGoClient(targetDir: java.io.File) {
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(openApiSpec.absolutePath)
    outputDir.set(targetDir.absolutePath)
    packageName.set("com.ladygo.driver.api.generated")
    apiPackage.set("com.ladygo.driver.api.generated.apis")
    modelPackage.set("com.ladygo.driver.api.generated.models")
    skipValidateSpec.set(false)
    configOptions.set(
        mapOf(
            // kotlinx_serialization was tried first but is a dead end: several admin-only response
            // models have a raw `Map<String, Object>` field (e.g. AdminAuditLogResponse.metadata)
            // that the generator does not mark @Contextual when nested inside a generic type
            // argument, and the kotlinx.serialization *compiler plugin* needs a statically-resolvable
            // serializer for every field at compile time - confirmed by a real compiler-internal
            // crash ("Serializer for element of type kotlin.Any has not been found"), not something
            // fixable from app code the way the one legitimately-@Contextual case (ApiResponseObject's
            // AnySerializer) was. Moshi's reflection-based adapters handle an unconstrained
            // Map<String, Any>/Any field at runtime instead, with no compile-time resolution problem.
            "serializationLibrary" to "moshi",
            "dateLibrary" to "string",
            "useCoroutines" to "true",
            "omitGradlePluginVersions" to "true",
            "omitGradleWrapper" to "true",
        ),
    )
    globalProperties.set(
        mapOf(
            "apiTests" to "false",
            "apiDocs" to "false",
            "modelTests" to "false",
            "modelDocs" to "false",
        ),
    )
}

// admin-auth-controller's generated file is deleted right after generation (both the committed
// client and the drift-check's fresh copy): its endpoints take cookie parameters
// (ladygoAdminRt/ladygoAdminCsrf), which the kotlin/jvm-retrofit2 generator cannot render - confirmed
// by generating it and finding `suspend fun logout1(, ): ...`, an empty parameter list that doesn't
// compile. Using the generator's own "apis" allowlist property to exclude just this one tag was tried
// first and silently produced an empty output directory instead (a generator quirk, not a config
// mistake - confirmed by reproducing it), so this deletes the one broken file directly instead.
// Not a loss either way: admin login is a different client's concern entirely (frontend/admin),
// never called from this app.
fun deleteAdminAuthControllerApi(targetDir: java.io.File) {
    targetDir.resolve("src/main/kotlin/com/ladygo/driver/api/generated/apis/AdminAuthControllerApi.kt").delete()
}

tasks.register<GenerateTask>("openApiGenerateClient") {
    group = "openapi"
    description = "Regenerates the committed API client in openapi-client/ from docs/api/openapi.json."
    configureLadyGoClient(generatedClientDir)
    doLast { deleteAdminAuthControllerApi(generatedClientDir) }
}

tasks.register<GenerateTask>("openApiGenerateFresh") {
    group = "openapi"
    description = "Generates a throwaway copy of the API client for the drift check to diff against."
    configureLadyGoClient(freshClientDir)
    doLast { deleteAdminAuthControllerApi(freshClientDir) }
}

tasks.register("checkOpenApiClientDrift") {
    group = "openapi"
    description = "Fails if the committed API client (openapi-client/) is stale relative to docs/api/openapi.json."
    dependsOn("openApiGenerateFresh")
    doLast {
        val freshSrc = freshClientDir.resolve("src/main/kotlin")
        if (!generatedClientSrc.exists()) {
            throw GradleException(
                "openapi-client/src/main/kotlin does not exist - run `gradle :app:openApiGenerateClient` and commit the result.",
            )
        }
        val committedFiles =
            generatedClientSrc
                .walkTopDown()
                .filter { it.isFile }
                .associateBy { it.relativeTo(generatedClientSrc).path }
        val freshFiles =
            freshSrc
                .walkTopDown()
                .filter { it.isFile }
                .associateBy { it.relativeTo(freshSrc).path }
        val missing = freshFiles.keys - committedFiles.keys
        val extra = committedFiles.keys - freshFiles.keys
        val changed =
            committedFiles.keys
                .intersect(freshFiles.keys)
                .filter { committedFiles[it]!!.readText() != freshFiles[it]!!.readText() }
        if (missing.isNotEmpty() || extra.isNotEmpty() || changed.isNotEmpty()) {
            throw GradleException(
                "openapi-client/ is out of date relative to docs/api/openapi.json.\n" +
                    "Missing: $missing\nExtra: $extra\nChanged: $changed\n" +
                    "Run `gradle :app:openApiGenerateClient` and commit the result.",
            )
        }
        logger.lifecycle("openapi-client/ matches docs/api/openapi.json.")
    }
}

android.sourceSets.getByName("main") {
    java.srcDir(generatedClientSrc)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt.yml"))
    source.setFrom(files("src/main/java", "src/test/java"))
}

ktlint {
    android.set(true)
    filter {
        exclude { it.file.path.contains("${File.separator}openapi-client${File.separator}") }
        exclude { it.file.path.contains("${File.separator}generated${File.separator}") }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    // Only pulled in because the generated infrastructure/ApiClient.kt (which this app doesn't
    // itself use - see NetworkModule's own comment) references it; required for that file to compile.
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
