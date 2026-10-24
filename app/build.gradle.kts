import java.util.UUID
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class GenerateP1RuntimeApi : JavaExec() {
    @get:Classpath abstract val appJars: ListProperty<RegularFile>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val appDirs: ListProperty<Directory>
    @get:Classpath abstract val testJars: ListProperty<RegularFile>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val testDirs: ListProperty<Directory>
    @get:Classpath abstract val appRuntime: ConfigurableFileCollection
    @get:Classpath abstract val testRuntime: ConfigurableFileCollection
    @get:Classpath abstract val platformLibraries: ConfigurableFileCollection
    @get:OutputDirectory abstract val rulesDirectory: DirectoryProperty
    @get:OutputDirectory abstract val preparedDirectory: DirectoryProperty

    @TaskAction override fun exec() {
        setArgs(listOf(rulesDirectory.get().asFile.absolutePath, preparedDirectory.get().asFile.absolutePath,
            "--lib") + platformLibraries.files.sorted().map { it.absolutePath } + listOf("--app") +
            (appRuntime.files + appJars.get().map { it.asFile } + appDirs.get().map { it.asFile }).sorted().map { it.absolutePath } +
            listOf("--test") +
            (testRuntime.files + testJars.get().map { it.asFile } + testDirs.get().map { it.asFile }).sorted().map { it.absolutePath })
        super.exec()
    }
}

// Opt-in instrumentation target; ordinary test selection and release signing stay unchanged.
val p2KdfBenchmark = providers.gradleProperty("p2KdfBenchmark").orElse("false").get().toBooleanStrict()
val p2ReleaseRuntime = providers.gradleProperty("p2ReleaseRuntime").orElse("false").get().toBooleanStrict()
val p3ReleaseRuntime = providers.gradleProperty("p3ReleaseRuntime").orElse("false").get().toBooleanStrict()
val p6b2ReleaseRuntime = providers.gradleProperty("p6b2ReleaseRuntime").orElse("false").get().toBooleanStrict()
val p6b1ReleaseRuntime = providers.gradleProperty("p6b1ReleaseRuntime").orElse("false").get().toBooleanStrict()
val p6aReleaseRuntime = providers.gradleProperty("p6aReleaseRuntime").orElse("false").get().toBooleanStrict()
val p5bReleaseRuntime = providers.gradleProperty("p5bReleaseRuntime").orElse("false").get().toBooleanStrict()
val p5aReleaseRuntime = providers.gradleProperty("p5aReleaseRuntime").orElse("false").get().toBooleanStrict()
val p4ReleaseRuntime = providers.gradleProperty("p4ReleaseRuntime").orElse("false").get().toBooleanStrict()
val p1RuntimeRequested = providers.gradleProperty("p1ReleaseRuntime").orElse("false").get().toBooleanStrict()
check(listOf(p1RuntimeRequested,p2ReleaseRuntime,p2KdfBenchmark,p3ReleaseRuntime,p4ReleaseRuntime,p5aReleaseRuntime,p5bReleaseRuntime,p6aReleaseRuntime,p6b1ReleaseRuntime,p6b2ReleaseRuntime).count { it } <= 1) { "Select one correctness suite OR explicit benchmark" }
val p1ReleaseRuntime = p1RuntimeRequested || p2ReleaseRuntime || p2KdfBenchmark || p3ReleaseRuntime || p4ReleaseRuntime || p5aReleaseRuntime || p5bReleaseRuntime || p6aReleaseRuntime || p6b1ReleaseRuntime || p6b2ReleaseRuntime
val cryptoRuntimeProfile = if(p6b2ReleaseRuntime) "p6b2" else if(p6b1ReleaseRuntime) "p6b1" else if(p6aReleaseRuntime) "p6a" else if(p5bReleaseRuntime) "p5b" else if(p5aReleaseRuntime) "p5a" else if(p4ReleaseRuntime) "p4" else if(p3ReleaseRuntime) "p3" else if (p2KdfBenchmark) "p2-benchmark" else if (p2ReleaseRuntime) "p2" else null
val runtimeReports = if(p6b2ReleaseRuntime) "p6b2-runtime" else if(p6b1ReleaseRuntime) "p6b1-runtime" else if(p6aReleaseRuntime) "p6a-runtime" else if(p5bReleaseRuntime) "p5b-runtime" else if(p5aReleaseRuntime) "p5a-runtime" else if(p4ReleaseRuntime) "p4-runtime" else if(p3ReleaseRuntime) "p3-runtime" else if (p2KdfBenchmark) "p2-benchmark" else if (p2ReleaseRuntime) "p2-runtime" else "p1-runtime"

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
            testInstrumentationRunnerArguments["class"] = "com.fantadrom.copperscroll.storage.runtime." +
                if(p6b2ReleaseRuntime) "P6b2PrivacyRuntimeTest" else if(p6b1ReleaseRuntime) "P6b1SessionRuntimeTest" else if(p6aReleaseRuntime) "P6aSafRuntimeTest" else if(p5bReleaseRuntime) "P5bChangeSecretRuntimeTest" else if(p5aReleaseRuntime) "P5aMaintenanceRuntimeTest" else if(p4ReleaseRuntime) "P4VfsCardRuntimeTest" else if(p3ReleaseRuntime) "P3TransactionRuntimeTest" else if (p2KdfBenchmark) "P2KdfBenchmark" else if (p2ReleaseRuntime) "P2CryptoRuntimeTest" else "P1BackendRuntimeTest"
        }
        if (p2KdfBenchmark) {
            for ((key, fallback) in mapOf("p2BenchmarkMemoryKiB" to "16384", "p2BenchmarkIterations" to "2",
                "p2BenchmarkSlots" to "1", "p2BenchmarkRepeats" to "20"))
                testInstrumentationRunnerArguments[key] = providers.gradleProperty(key).orElse(fallback).get()
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
    sourceSets {
        getByName("debug").kotlin.directories.add("src/productionPolicy/kotlin")
        getByName("release").kotlin.directories.add("src/productionPolicy/kotlin")
        if(!p6aReleaseRuntime && !p6b1ReleaseRuntime && !p6b2ReleaseRuntime) getByName("p1Release").kotlin.directories.add("src/productionPolicy/kotlin")
        if(p6aReleaseRuntime || p6b1ReleaseRuntime || p6b2ReleaseRuntime) {
            getByName("androidTest").kotlin.directories.add("src/p6aAndroidTest/kotlin")
            getByName("androidTest").java.directories.add("src/p6aAndroidTest/java")
            getByName("androidTest").manifest.srcFile("src/p6aAndroidTest/AndroidManifest.xml")
            getByName("androidTest").assets.directories.add("src/p6aAndroidTest/assets")
            getByName("p1Release").kotlin.directories.add("src/p6aHarness/kotlin")
            getByName("p1Release").kotlin.directories.add("src/p6aModel/kotlin")
            getByName("p1Release").manifest.srcFile(if(p6b2ReleaseRuntime) "src/p6b2Harness/AndroidManifest.xml" else if(p6b1ReleaseRuntime) "src/p6b1Harness/AndroidManifest.xml" else "src/p6aHarness/AndroidManifest.xml")
            if(p6b1ReleaseRuntime || p6b2ReleaseRuntime) {
                getByName("p1Release").kotlin.directories.addAll(listOf("src/p6b1Harness/kotlin","src/p6b1Model/kotlin"))
                getByName("androidTest").kotlin.directories.add("src/p6b1AndroidTest/kotlin")
            }
        }
        if(p6b2ReleaseRuntime) {
            getByName("p1Release").kotlin.directories.addAll(listOf("src/p6b2Harness/kotlin","src/p6b2Model/kotlin"))
            getByName("androidTest").kotlin.directories.add("src/p6b2AndroidTest/kotlin")
            getByName("androidTest").java.directories.add("src/p6b2AndroidTest/java")
        }
        getByName("test").kotlin.directories.add("src/p6b2Model/kotlin")
        getByName("test").kotlin.directories.add("src/testShared/kotlin")
        getByName("test").kotlin.directories.add("src/p6aModel/kotlin")
        getByName("test").kotlin.directories.add("src/p6b1Model/kotlin")
        getByName("androidTest").kotlin.directories.add("src/testShared/kotlin")
        if (p2KdfBenchmark) getByName("androidTest").kotlin.directories.add("src/p2Benchmark/kotlin")
    }
}

dependencies {
    implementation(libs.bcprov)
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
    // The P1 backend suite uses AndroidJUnitRunner/JUnit, without Compose/Espresso APIs.
    // Keep the ordinary debug UI-test setup; do not export its unused shared ABI to p1Release.
    if (p1ReleaseRuntime) {
        androidTestImplementation(libs.androidx.test.runner)
    } else {
        androidTestImplementation(platform(libs.androidx.compose.bom))
        androidTestImplementation(libs.androidx.compose.ui.test.junit4)
        androidTestImplementation(libs.androidx.espresso.core)
    }
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Host-only reader for the protocol ALREADY used by AGP9.4.1's Android test engine.
// Not an app/androidTest dependency; no backend or protobuf copy is added to either APK.
val p1ResultProtocol = configurations.create("p1ResultProtocol") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.add(p1ResultProtocol.name, "com.android.tools.androidtest:test-result-proto:1.0.1")
val compileP1ResultVerifier = tasks.register<JavaCompile>("compileP1ResultVerifier") {
    source(rootProject.file("tools/p1/VerifyRuntimeResults.java"), rootProject.file("tools/p1/VerifyRuntimeResultsTest.java"),
        rootProject.file("tools/p1/RuntimeDiagnostics.java"),rootProject.file("tools/p6a/RuntimeDiagnosticsTest.java"),
        rootProject.file("tools/p2/VerifyP2RuntimeResultsTest.java"), rootProject.file("tools/p3/VerifyP3RuntimeResultsTest.java"),
        rootProject.file("tools/p4/RuntimeSourceIdentity.java"),rootProject.file("tools/p4/VerifyP4RuntimeResultsTest.java"),
        rootProject.file("tools/p4/RuntimeSourceIdentityTest.java"),rootProject.file("tools/p5a/VerifyP5aRuntimeResultsTest.java"),rootProject.file("tools/p5b/VerifyP5bRuntimeResultsTest.java"),rootProject.file("tools/p6a/VerifyP6aRuntimeResultsTest.java"),rootProject.file("tools/p6a/ProviderProbeController.java"),
        rootProject.file("tools/p6b2/VerifyP6b2RuntimeResultsTest.java"),rootProject.file("tools/p6b2/PrivacyProbeController.java"),rootProject.file("tools/p6b1/VerifyP6b1RuntimeResultsTest.java"),rootProject.file("tools/p6b1/LifecycleProbeController.java"),rootProject.file("tools/p6a2/QualificationLedger.java"),rootProject.file("tools/p6a2/QualificationController.java"),rootProject.file("tools/p6a2/QualificationLedgerTest.java"))
    classpath = p1ResultProtocol
    destinationDirectory.set(layout.buildDirectory.dir("p1-result-verifier/classes"))
    options.release.set(11)
}
val testP1RuntimeResults = tasks.register<JavaExec>("testP1RuntimeResults") {
    group = "verification"
    description = "Exercise the host P1 result gate with passing/failing XML fixtures, without Android."
    dependsOn(compileP1ResultVerifier)
    classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory }, p1ResultProtocol)
    mainClass.set("VerifyRuntimeResultsTest")
}
tasks.named("check") { dependsOn(testP1RuntimeResults) }
val testP6aRuntimeDiagnostics = tasks.register<JavaExec>("testP6aRuntimeDiagnostics") {
    group = "verification"
    dependsOn(compileP1ResultVerifier)
    classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
    mainClass.set("RuntimeDiagnosticsTest")
}
tasks.named("check") { dependsOn(testP6aRuntimeDiagnostics) }
val testP2RuntimeResults = tasks.register<JavaExec>("testP2RuntimeResults") {
    group = "verification"
    dependsOn(compileP1ResultVerifier)
    classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory }, p1ResultProtocol)
    mainClass.set("VerifyP2RuntimeResultsTest")
}
tasks.named("check") { dependsOn(testP2RuntimeResults) }
val testP3RuntimeResults = tasks.register<JavaExec>("testP3RuntimeResults") {
    group = "verification"
    dependsOn(compileP1ResultVerifier)
    classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory }, p1ResultProtocol)
    mainClass.set("VerifyP3RuntimeResultsTest")
}
tasks.named("check") { dependsOn(testP3RuntimeResults) }
val testP4RuntimeResults = tasks.register<JavaExec>("testP4RuntimeResults") {
    group = "verification"
    dependsOn(compileP1ResultVerifier)
    classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory }, p1ResultProtocol)
    mainClass.set("VerifyP4RuntimeResultsTest")
}
val testP4SourceIdentity = tasks.register<JavaExec>("testP4SourceIdentity") {
    group = "verification"
    dependsOn(compileP1ResultVerifier)
    classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory }, p1ResultProtocol)
    mainClass.set("RuntimeSourceIdentityTest")
}
tasks.named("check") { dependsOn(testP4RuntimeResults,testP4SourceIdentity) }

val testP5aRuntimeResults = tasks.register<JavaExec>("testP5aRuntimeResults") {
    group = "verification"
    dependsOn(compileP1ResultVerifier)
    classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
    mainClass.set("VerifyP5aRuntimeResultsTest")
}
tasks.named("check") { dependsOn(testP5aRuntimeResults) }

val testP5bRuntimeResults = tasks.register<JavaExec>("testP5bRuntimeResults") {
    group = "verification"
    dependsOn(compileP1ResultVerifier)
    classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
    mainClass.set("VerifyP5bRuntimeResultsTest")
}
tasks.named("check") { dependsOn(testP5bRuntimeResults) }

val testP6aRuntimeResults = tasks.register<JavaExec>("testP6aRuntimeResults") {
    group = "verification"
    dependsOn(compileP1ResultVerifier)
    classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
    mainClass.set("VerifyP6aRuntimeResultsTest")
}
tasks.named("check") { dependsOn(testP6aRuntimeResults) }
val testP6a2Qualification = tasks.register<JavaExec>("testP6a2Qualification") {
    group = "verification"
    dependsOn(compileP1ResultVerifier)
    classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
    mainClass.set("QualificationLedgerTest")
    args(rootProject.projectDir.absolutePath)
}
tasks.named("check") { dependsOn(testP6a2Qualification) }


if (p1ReleaseRuntime) {
    val p4IdentityFile = layout.buildDirectory.file("reports/$runtimeReports/build-identity.properties")
    val captureP4BuildIdentity = if(p4ReleaseRuntime || p5aReleaseRuntime || p5bReleaseRuntime || p6aReleaseRuntime || p6b1ReleaseRuntime || p6b2ReleaseRuntime) tasks.register<JavaExec>("captureP4BuildIdentity") {
        dependsOn(compileP1ResultVerifier)
        classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
        mainClass.set("RuntimeSourceIdentity")
        args("capture",rootProject.projectDir.absolutePath,p4IdentityFile.get().asFile.absolutePath,
            org.gradle.util.GradleVersion.current().version,System.getProperty("java.runtime.version"),System.getProperty("java.vendor"))
        outputs.upToDateWhen { false }
    } else null
    if(captureP4BuildIdentity != null) tasks.matching {
        it.name == "preP1ReleaseBuild" || it.name == "preP1ReleaseAndroidTestBuild"
    }.configureEach { dependsOn(captureP4BuildIdentity) }
    // Error Prone annotations are compile-time metadata; IncompatibleModifiers references a JDK-only enum.
    // Exclude only from runtime; the compile configuration is not excluded. Production is unchanged.
    configurations.matching { it.name == "p1ReleaseAndroidTestRuntimeClasspath" }.configureEach {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    // Use the R8 bundled with the current AGP, uninstrumented, on the HOST only.
    val p1R8Tool = configurations.create("p1R8Tool") { isCanBeConsumed = false }
    dependencies.add(p1R8Tool.name, "com.android.tools.build:builder:${libs.versions.agp.get()}") { isTransitive = false }
    val compileP1RuntimeApi = tasks.register<JavaCompile>("compileP1RuntimeApi") {
        source(rootProject.file("tools/p1/PrepareRuntimeApi.java"))
        classpath = p1R8Tool
        destinationDirectory.set(layout.buildDirectory.dir("p1-runtime-api/classes"))
        options.release.set(11)
    }
    androidComponents.onVariants(androidComponents.selector().withBuildType("p1Release")) { variant ->
        val instrumentation = checkNotNull(variant.androidTest)
        val boot = androidComponents.sdkComponents.bootClasspath.get().map { it.asFile }
        val optional = boot.filter { it.name == "android.jar" }.flatMap { jar ->
            listOf("android.test.base.jar", "android.test.mock.jar", "android.test.runner.jar")
                .map { jar.parentFile.resolve("optional/$it") }.filter { it.isFile }
        }
        val generateApi = tasks.register<GenerateP1RuntimeApi>("generateP1RuntimeApi") {
            dependsOn(compileP1RuntimeApi)
            classpath = files(compileP1RuntimeApi.flatMap { it.destinationDirectory }, p1R8Tool)
            mainClass.set("PrepareRuntimeApi")
            maxHeapSize = "2g"
            appRuntime.from(variant.runtimeConfiguration)
            testRuntime.from(instrumentation.runtimeConfiguration)
            platformLibraries.from(boot, optional)
            rulesDirectory.set(layout.buildDirectory.dir("generated/p1-runtime-api/keepRules"))
            preparedDirectory.set(layout.buildDirectory.dir("intermediates/p1-runtime-api"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(generateApi)
            .toGet(ScopedArtifact.CLASSES, GenerateP1RuntimeApi::appJars, GenerateP1RuntimeApi::appDirs)
        instrumentation.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(generateApi)
            .toGet(ScopedArtifact.CLASSES, GenerateP1RuntimeApi::testJars, GenerateP1RuntimeApi::testDirs)
        variant.sources.keepRules!!.addGeneratedSourceDirectory(generateApi, GenerateP1RuntimeApi::rulesDirectory)
    }
    val compileP1ApkVerifier = tasks.register<JavaCompile>("compileP1ApkVerifier") {
        source(rootProject.file("tools/p1/VerifyRuntimeApks.java"), rootProject.file("tools/p1/DexRuntimeLinkage.java"),
            rootProject.file("tools/p1/P1ArtifactDiagnostics.java"),rootProject.file("tools/p1/P6aManifestAudit.java"),rootProject.file("tools/p1/P6b1ManifestAudit.java"),rootProject.file("tools/p1/P6b1ManifestAuditTest.java"))
        classpath = files()
        destinationDirectory.set(layout.buildDirectory.dir("p1-apk-verifier/classes"))
        options.release.set(11)
    }
    val testP6b1BackupAudit=tasks.register<JavaExec>("testP6b1BackupAudit") {
        dependsOn(compileP1ApkVerifier)
        classpath=files(compileP1ApkVerifier.flatMap { it.destinationDirectory })
        mainClass.set("P6b1ManifestAuditTest")
    }
    val verifyP1ReleaseApks = tasks.register<JavaExec>("verifyP1ReleaseApks") {
        group = "verification"
        description = "Audit the processed P1 release target and ensure the test APK contains no backend copy."
        dependsOn("assembleP1Release", "assembleP1ReleaseAndroidTest", compileP1ApkVerifier,testP6b1BackupAudit)
        classpath = files(compileP1ApkVerifier.flatMap { it.destinationDirectory })
        mainClass.set("VerifyRuntimeApks")
        systemProperty("p6b1.aapt2",androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve("build-tools/${android.buildToolsVersion}/aapt2${if(System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}").absolutePath)
        args(
            layout.buildDirectory.file("outputs/apk/p1Release/app-p1Release.apk").get().asFile.absolutePath,
            layout.buildDirectory.file("outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk").get().asFile.absolutePath,
            rootProject.file("app/src/test/resources/p1/settings-default.hex").absolutePath,
            layout.buildDirectory.file("reports/$runtimeReports/apk-audit.txt").get().asFile.absolutePath,
            layout.buildDirectory.file("outputs/mapping/p1Release/mapping.txt").get().asFile.absolutePath,
            layout.buildDirectory.file("outputs/mapping/p1ReleaseAndroidTest/mapping.txt").get().asFile.absolutePath,
            layout.buildDirectory.dir("reports/$runtimeReports").get().asFile.absolutePath,
            layout.buildDirectory.file("generated/p1-runtime-api/keepRules/instrumentation-api.keep").get().asFile.absolutePath
        )
        val boot = androidComponents.sdkComponents.bootClasspath.get().map { it.asFile }
        args(boot.map { it.absolutePath })
        args(boot.filter { it.name == "android.jar" }.flatMap { jar ->
            listOf("android.test.base.jar", "android.test.mock.jar", "android.test.runner.jar")
                .map { jar.parentFile.resolve("optional/$it") }.filter { it.isFile }.map { it.absolutePath }
        })
    }
    val verifyP4BuildIdentity = if(p4ReleaseRuntime || p5aReleaseRuntime || p5bReleaseRuntime || p6aReleaseRuntime || p6b1ReleaseRuntime || p6b2ReleaseRuntime) tasks.register<JavaExec>("verifyP4BuildIdentity") {
        dependsOn(verifyP1ReleaseApks,checkNotNull(captureP4BuildIdentity))
        classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
        mainClass.set("RuntimeSourceIdentity")
        args("verify",rootProject.projectDir.absolutePath,p4IdentityFile.get().asFile.absolutePath)
    } else null
    val prepareP1RuntimeRun = tasks.register<JavaExec>("prepareP1RuntimeRun") {
        description = "Discard previous P1 results and snapshot selected online devices for this invocation."
        dependsOn(verifyP1ReleaseApks, testP1RuntimeResults)
        if (cryptoRuntimeProfile != null) dependsOn(testP2RuntimeResults)
        if (p3ReleaseRuntime) dependsOn(testP3RuntimeResults)
        if (p4ReleaseRuntime || p5aReleaseRuntime || p5bReleaseRuntime || p6aReleaseRuntime || p6b1ReleaseRuntime || p6b2ReleaseRuntime) dependsOn(testP4RuntimeResults,testP4SourceIdentity,checkNotNull(verifyP4BuildIdentity))
        if (p5aReleaseRuntime || p5bReleaseRuntime || p6aReleaseRuntime || p6b1ReleaseRuntime || p6b2ReleaseRuntime) dependsOn(testP5aRuntimeResults)
        if (p5bReleaseRuntime || p6aReleaseRuntime || p6b1ReleaseRuntime || p6b2ReleaseRuntime) dependsOn(testP5bRuntimeResults)
        if (p6aReleaseRuntime || p6b1ReleaseRuntime || p6b2ReleaseRuntime) dependsOn(testP6aRuntimeResults,testP6aRuntimeDiagnostics, "verifyP6aIsolation")
        classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory }, p1ResultProtocol)
        mainClass.set("VerifyRuntimeResults")
        args("prepare", layout.buildDirectory.get().asFile.absolutePath,
            androidComponents.sdkComponents.adb.get().asFile.absolutePath)
        if (cryptoRuntimeProfile != null) args(cryptoRuntimeProfile)
    }
    if(p6b2ReleaseRuntime) prepareP1RuntimeRun.configure { dependsOn("testP6b2RuntimeResults") }
    if(p6b1ReleaseRuntime) prepareP1RuntimeRun.configure { dependsOn("testP6b1RuntimeResults") }
    if(p6b1ReleaseRuntime) tasks.register<JavaExec>("p6b1LifecycleProbe") {
        group="verification"
        dependsOn(verifyP1ReleaseApks,checkNotNull(verifyP4BuildIdentity),"verifyP6aIsolation")
        classpath=files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
        mainClass.set("LifecycleProbeController")
        args(layout.buildDirectory.get().asFile.absolutePath,androidComponents.sdkComponents.adb.get().asFile.absolutePath)
        notCompatibleWithConfigurationCache("Explicit interactive lifecycle diagnostic")
    }
    if(p6b2ReleaseRuntime) tasks.register<JavaExec>("verifyP6b2OrdinaryRelease") {
        dependsOn("assembleRelease",compileP1ApkVerifier)
        classpath=files(compileP1ApkVerifier.flatMap { it.destinationDirectory })
        mainClass.set("VerifyRuntimeApks")
        systemProperty("p6b1.aapt2",androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve("build-tools/${android.buildToolsVersion}/aapt2${if(System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}").absolutePath)
        args("--ordinary-release",layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile.absolutePath,
            layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile.absolutePath,
            layout.buildDirectory.file("reports/p6b2-runtime/ordinary-release-audit.properties").get().asFile.absolutePath)
    }
    if(p6b2ReleaseRuntime) for(mode in listOf("privacy","process","handoff","backup","release")) {
        tasks.register<JavaExec>("p6b2"+mode.replaceFirstChar { it.uppercase() }+"Probe") {
            group="verification"
            dependsOn(verifyP1ReleaseApks,checkNotNull(verifyP4BuildIdentity),"verifyP6aIsolation","verifyP6b2OrdinaryRelease","testP6b2RuntimeResults")
            classpath=files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
            mainClass.set("PrivacyProbeController")
            args(mode,layout.buildDirectory.get().asFile.absolutePath,androidComponents.sdkComponents.adb.get().asFile.absolutePath,
                androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve("build-tools/${android.buildToolsVersion}").absolutePath,
                checkNotNull(android.signingConfigs.getByName("debug").storeFile).absolutePath)
            notCompatibleWithConfigurationCache("Explicit host-controlled Android privacy evidence")
        }
    }
    val connectedP1 = tasks.matching { it.name == "connectedP1ReleaseAndroidTest" }
    connectedP1.configureEach {
        dependsOn(prepareP1RuntimeRun)
        outputs.upToDateWhen { false }
    }
    val runtimeCheck=tasks.register<JavaExec>(if(p6b2ReleaseRuntime) "p6b2ReleaseRuntimeCheck" else if(p6b1ReleaseRuntime) "p6b1ReleaseRuntimeCheck" else if(p6aReleaseRuntime) "p6aReleaseRuntimeCheck" else if(p5bReleaseRuntime) "p5bReleaseRuntimeCheck" else if(p5aReleaseRuntime) "p5aReleaseRuntimeCheck" else if(p4ReleaseRuntime) "p4ReleaseRuntimeCheck" else if(p3ReleaseRuntime) "p3ReleaseRuntimeCheck" else if (p2KdfBenchmark) "p2KdfBenchmarkCheck" else if (p2ReleaseRuntime) "p2ReleaseRuntimeCheck" else "p1ReleaseRuntimeCheck") {
        group = "verification"
        description = "Run fresh $runtimeReports processed-APK tests and enforce its exact suite on EVERY selected device."
        dependsOn(connectedP1)
        classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory }, p1ResultProtocol)
        mainClass.set("VerifyRuntimeResults")
        args("verify", layout.buildDirectory.get().asFile.absolutePath,
            androidComponents.sdkComponents.adb.get().asFile.absolutePath)
        if (cryptoRuntimeProfile != null) args(cryptoRuntimeProfile)
    }
    if(p6aReleaseRuntime || p6b1ReleaseRuntime || p6b2ReleaseRuntime) {
        val diagnostics=tasks.register<JavaExec>("collectP6aRuntimeDiagnostics") {
            dependsOn(compileP1ResultVerifier)
            mustRunAfter(connectedP1,runtimeCheck)
            classpath=files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
            mainClass.set("VerifyRuntimeResults")
            args("diagnose",layout.buildDirectory.get().asFile.absolutePath,
                androidComponents.sdkComponents.adb.get().asFile.absolutePath,if(p6b2ReleaseRuntime) "p6b2" else if(p6b1ReleaseRuntime) "p6b1" else "p6a")
        }
        prepareP1RuntimeRun.configure { finalizedBy(diagnostics) }
        connectedP1.configureEach { finalizedBy(diagnostics) }
        runtimeCheck.configure { finalizedBy(diagnostics) }
        tasks.register<JavaExec>("verifyP6aIsolation") {
            dependsOn(verifyP1ReleaseApks,"assembleRelease")
            classpath = files(compileP1ApkVerifier.flatMap { it.destinationDirectory })
            mainClass.set("VerifyRuntimeApks")
        systemProperty("p6b1.aapt2",androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve("build-tools/${android.buildToolsVersion}/aapt2${if(System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}").absolutePath)
            args("--p6a-isolation",layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile.absolutePath,
                layout.buildDirectory.file("outputs/apk/p1Release/app-p1Release.apk").get().asFile.absolutePath)
        }
        // Build failures happen before the host controller can start. Capture this invocation's
        // FIRST failed task even then; old audit/runtime artifacts are explicitly excluded.
        val qualificationRequested = p6b2ReleaseRuntime || p6b1ReleaseRuntime || gradle.startParameter.taskNames.any { name -> listOf("p6a2ProcessQualification","p6a2RebootPrepare","p6a2RebootResume").any { name.endsWith(it) } }
        val qualificationAttempt = UUID.randomUUID().toString()
        val buildDiagnosticTask=if(p6b2ReleaseRuntime) "collectP6b2BuildDiagnostics" else if(p6b1ReleaseRuntime) "collectP6b1BuildDiagnostics" else "collectP6a2BuildDiagnostics"
        val qualificationDiagnostics = if(qualificationRequested) tasks.register(buildDiagnosticTask) {
            notCompatibleWithConfigurationCache("Collects failed task states for this explicit diagnostic invocation")
            doLast {
                val directory=layout.buildDirectory.dir("reports/${if(p6b2ReleaseRuntime) "p6b2" else if(p6b1ReleaseRuntime) "p6b1" else "p6a2"}-build-attempts/$qualificationAttempt").get().asFile
                directory.mkdirs()
                val failed=gradle.taskGraph.allTasks.filter { it.state.failure!=null }
                val text=buildString {
                    appendLine("attemptId=$qualificationAttempt")
                    appendLine("AndroidRuntime=NOT_INFERRED_FROM_BUILD")
                    appendLine("failedTaskCount=${failed.size}")
                    failed.forEach { appendLine("task=${it.path}"); appendLine(it.state.failure!!.stackTraceToString().take(32768)) }
                    val audited=tasks.named("verifyP1ReleaseApks").get().state
                    if(audited.executed && audited.failure==null && audited.didWork) {
                        val zip=layout.buildDirectory.file("reports/$runtimeReports/p1-diagnostics.zip").get().asFile
                        if(zip.isFile) zip.copyTo(directory.resolve("current-artifact-diagnostics.zip"),overwrite=true)
                        appendLine("artifactEvidence=CURRENT_AUDIT_ONLY")
                    } else appendLine("artifactEvidence=NOT_AVAILABLE_CURRENT_AUDIT_DID_NOT_PASS")
                }
                directory.resolve("build-diagnostics.txt").writeText(text)
                val archive=directory.resolve("build-diagnostics.zip")
                ZipOutputStream(archive.outputStream()).use { zip ->
                    directory.listFiles()!!.filter { it!=archive && it.isFile }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
                logger.lifecycle("$runtimeReports build diagnostics: ${archive.absolutePath}")
            }
        } else null
        if(qualificationDiagnostics!=null) {
            qualificationDiagnostics.configure { mustRunAfter(tasks.matching { it.name!=buildDiagnosticTask }) }
            tasks.matching { it.name!=buildDiagnosticTask }.configureEach {
                finalizedBy(qualificationDiagnostics)
            }
        }
        for ((name,mode) in mapOf("p6a2ProcessQualification" to "process", "p6a2RebootPrepare" to "rebootPrepare", "p6a2RebootResume" to "rebootResume")) {
            tasks.register<JavaExec>(name) {
                group = "verification"
                dependsOn(verifyP1ReleaseApks,checkNotNull(verifyP4BuildIdentity),"verifyP6aIsolation",testP6a2Qualification,testP4SourceIdentity)
                classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
                if(qualificationDiagnostics!=null) finalizedBy(qualificationDiagnostics)
                mainClass.set("QualificationController")
                args(mode,layout.buildDirectory.get().asFile.absolutePath,androidComponents.sdkComponents.adb.get().asFile.absolutePath,
                    providers.gradleProperty("p6a2RunId").orElse("new").get())
            }
        }
        tasks.register<JavaExec>("p6aProviderProbe") {
            group = "verification"
            dependsOn(verifyP1ReleaseApks,checkNotNull(verifyP4BuildIdentity),"verifyP6aIsolation")
            classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
            mainClass.set("ProviderProbeController")
            args(layout.buildDirectory.get().asFile.absolutePath,androidComponents.sdkComponents.adb.get().asFile.absolutePath)
        }
    }

}

val testP6b1RuntimeResults=tasks.register<JavaExec>("testP6b1RuntimeResults") {
    dependsOn(compileP1ResultVerifier)
    classpath=files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
    mainClass.set("VerifyP6b1RuntimeResultsTest")
}
tasks.named("check") { dependsOn(testP6b1RuntimeResults) }

val testP6b2RuntimeResults=tasks.register<JavaExec>("testP6b2RuntimeResults") {
    dependsOn(compileP1ResultVerifier)
    classpath=files(compileP1ResultVerifier.flatMap { it.destinationDirectory },p1ResultProtocol)
    mainClass.set("VerifyP6b2RuntimeResultsTest")
}
tasks.named("check") { dependsOn(testP6b2RuntimeResults) }
