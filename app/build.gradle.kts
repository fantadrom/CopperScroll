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
    source(rootProject.file("tools/p1/VerifyRuntimeResults.java"), rootProject.file("tools/p1/VerifyRuntimeResultsTest.java"))
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

if (p1ReleaseRuntime) {
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
            rootProject.file("tools/p1/P1ArtifactDiagnostics.java"))
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
            layout.buildDirectory.file("reports/p1-runtime/apk-audit.txt").get().asFile.absolutePath,
            layout.buildDirectory.file("outputs/mapping/p1Release/mapping.txt").get().asFile.absolutePath,
            layout.buildDirectory.file("outputs/mapping/p1ReleaseAndroidTest/mapping.txt").get().asFile.absolutePath,
            layout.buildDirectory.dir("reports/p1-runtime").get().asFile.absolutePath,
            layout.buildDirectory.file("generated/p1-runtime-api/keepRules/instrumentation-api.keep").get().asFile.absolutePath
        )
        val boot = androidComponents.sdkComponents.bootClasspath.get().map { it.asFile }
        args(boot.map { it.absolutePath })
        args(boot.filter { it.name == "android.jar" }.flatMap { jar ->
            listOf("android.test.base.jar", "android.test.mock.jar", "android.test.runner.jar")
                .map { jar.parentFile.resolve("optional/$it") }.filter { it.isFile }.map { it.absolutePath }
        })
    }
    val prepareP1RuntimeRun = tasks.register<JavaExec>("prepareP1RuntimeRun") {
        description = "Discard previous P1 results and snapshot selected online devices for this invocation."
        dependsOn(verifyP1ReleaseApks, testP1RuntimeResults)
        classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory }, p1ResultProtocol)
        mainClass.set("VerifyRuntimeResults")
        args("prepare", layout.buildDirectory.get().asFile.absolutePath,
            androidComponents.sdkComponents.adb.get().asFile.absolutePath)
    }
    val connectedP1 = tasks.matching { it.name == "connectedP1ReleaseAndroidTest" }
    connectedP1.configureEach {
        dependsOn(prepareP1RuntimeRun)
        outputs.upToDateWhen { false }
    }
    tasks.register<JavaExec>("p1ReleaseRuntimeCheck") {
        group = "verification"
        description = "Run fresh processed-APK tests and require exactly seven P1 tests on EVERY selected device."
        dependsOn(connectedP1)
        classpath = files(compileP1ResultVerifier.flatMap { it.destinationDirectory }, p1ResultProtocol)
        mainClass.set("VerifyRuntimeResults")
        args("verify", layout.buildDirectory.get().asFile.absolutePath,
            androidComponents.sdkComponents.adb.get().asFile.absolutePath)
    }
}
