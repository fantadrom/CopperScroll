plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Opt-in instrumentation target; ordinary debug/release behavior and signing stay unchanged.
val p1ReleaseRuntime = providers.gradleProperty("p1ReleaseRuntime").orElse("false").get().toBooleanStrict()

android {
    namespace = "com.fantadrom.copperscroll"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.fantadrom.copperscroll"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["p1ExpectedVariant"] = if (p1ReleaseRuntime) "p1Release" else "debug"
        if (p1ReleaseRuntime) {
            testInstrumentationRunnerArguments["class"] = "com.fantadrom.copperscroll.storage.runtime.P1BackendRuntimeTest"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    // Same production sources/dependencies and R8 scope as release; installable with a local test key.
    buildTypes.create("p1Release") {
        val productionRelease = buildTypes.getByName("release")
        initWith(productionRelease)
        check(optimization.enable && optimization.packageScope.get() == productionRelease.optimization.packageScope.get()) {
            "P1 runtime target must retain release R8 enable/scope"
        }
        check(!isDebuggable) { "P1 runtime target must not be debuggable" }
        applicationIdSuffix = ".p1runtime"
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += "release"
    }
    testBuildType = if (p1ReleaseRuntime) "p1Release" else "debug"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.cbor)
    implementation(libs.icu4j)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

if (p1ReleaseRuntime) {
    // Error Prone annotations are compile-time metadata; IncompatibleModifiers references a JDK-only enum.
    // Keep them on the compile classpath, not in the R8-processed instrumentation APK. Production is unchanged.
    configurations.matching { it.name == "p1ReleaseAndroidTestRuntimeClasspath" }.configureEach {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    val compileP1ApkVerifier = tasks.register<JavaCompile>("compileP1ApkVerifier") {
        source(rootProject.file("tools/p1/VerifyRuntimeApks.java"))
        classpath = files()
        destinationDirectory.set(layout.buildDirectory.dir("p1-apk-verifier/classes"))
        options.release.set(11)
    }
    val verifyP1ReleaseApks = tasks.register<JavaExec>("verifyP1ReleaseApks") {
        group = "verification"
        description = "Audit the processed P1 release target and ensure the test APK contains no backend copy."
        dependsOn("assembleP1Release", "assembleP1ReleaseAndroidTest", compileP1ApkVerifier)
        classpath = files(compileP1ApkVerifier.flatMap { it.destinationDirectory })
        mainClass.set("VerifyRuntimeApks")
        args(
            layout.buildDirectory.file("outputs/apk/p1Release/app-p1Release.apk").get().asFile.absolutePath,
            layout.buildDirectory.file("outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk").get().asFile.absolutePath,
            rootProject.file("app/src/test/resources/p1/settings-default.hex").absolutePath,
            layout.buildDirectory.file("reports/p1-runtime/apk-audit.txt").get().asFile.absolutePath
        )
    }
    tasks.matching { it.name == "connectedP1ReleaseAndroidTest" }.configureEach {
        dependsOn(verifyP1ReleaseApks)
    }
}
