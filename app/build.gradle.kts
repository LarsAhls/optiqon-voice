import java.util.Calendar

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// google-services.json is downloaded per machine and is deliberately gitignored, so the
// plugin is applied only when the file is actually present. Without it the app still
// builds and its non-Firebase tests still run; what disappears is the generated Firebase
// configuration, which FirebaseInitializer reports as "not configured" at runtime.
val googleServicesConfig = file("google-services.json")
if (googleServicesConfig.exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
} else {
    logger.lifecycle(
        "google-services.json is missing: building without Firebase configuration. " +
            "Download it from the Firebase console into app/ before signing in."
    )
}

// Firebase Auth sends its email sign-in links through <project-id>.firebaseapp.com before
// they reach the Hosting continue-URL, so the manifest needs a filter for that host. The host is
// read out of the same config the Firebase SDK reads rather than written down twice: the
// project id is not guessable from the project name (this one is optiqon-voice-47498), and a
// filter for a host the project does not own is a filter that silently never fires.
//
// Only project_id is taken. Nothing else from the file is read, logged, or embedded here.
val firebaseProjectId: String? = if (googleServicesConfig.exists()) {
    (
        groovy.json.JsonSlurper().parse(googleServicesConfig) as Map<*, *>
    ).let { it["project_info"] as Map<*, *> }["project_id"] as String
} else {
    null
}
val firebaseAuthHost: String = if (firebaseProjectId != null) {
    "$firebaseProjectId.firebaseapp.com"
} else {
    // A build without Firebase configuration has no project to name. `.invalid` is reserved
    // by RFC 2606 and never resolves, so the filter stays syntactically valid and inert
    // instead of pointing at somebody else's host.
    "unconfigured.invalid"
}

android {
    namespace = "se.optiqon.voice"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "se.optiqon.voice"
        // API 28 (Android 9), not 26. APK Signature Scheme v3 — and therefore key rotation —
        // exists from API 28, so on API 26-27 the signer of an installed app can never change
        // in an update: the debug key the beta is signed with today would be permanent there.
        // Decided in sitting 1 (docs/DECISION_SHEET_2026-09-10.md, D8) on the ground that no
        // tester is on Android 8.x. See docs/BETA_SIGNING_AND_DISTRIBUTION.md.
        minSdk = 28
        targetSdk = 35

        val tag = findProperty("versionTag")?.toString()?.removePrefix("v") ?: ""
        val cal = Calendar.getInstance()
        val dateCode = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)

        // Tags are dated, optionally with a same-day patch: v20260813, v20260813.2.
        // Parsing the whole tag as an Int returned null for the patch form and fell back
        // to the date, so v20260813.2 shipped the same versionCode as v20260813.1.
        // Android decides updates by versionCode, so the patch never reached anyone.
        // Date and patch are packed separately, leaving codes ordered across days.
        val tagParts = tag.split(".")
        val baseCode = tagParts.getOrNull(0)?.toIntOrNull() ?: dateCode
        val patchCode = tagParts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 99) ?: 0
        versionCode = baseCode * 100 + patchCode
        versionName = if (tag.isNotBlank()) "v$tag" else "v$baseCode"

        manifestPlaceholders["firebaseAuthHost"] = firebaseAuthHost
        // The same project id, for the email sign-in continue URL
        // (https://<project_id>.web.app/signin). Empty when there is no configuration, which
        // the sign-in client reports as "email link unavailable" rather than sending a link
        // that continues to nowhere.
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseProjectId ?: ""}\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootProject.projectDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // Future release signing key, provided via env vars / Gradle properties (a future
        // "Gate" mission's GitHub secrets), never committed to the repo. No values are set
        // here, so this config is only usable once all four are supplied out-of-band; until
        // then `release.signingConfig` below falls back to the debug key, same as today.
        create("release") {
            val storeFilePath = providers.gradleProperty("RELEASE_STORE_FILE")
                .orElse(providers.environmentVariable("RELEASE_STORE_FILE"))
            val storePasswordValue = providers.gradleProperty("RELEASE_STORE_PASSWORD")
                .orElse(providers.environmentVariable("RELEASE_STORE_PASSWORD"))
            val keyAliasValue = providers.gradleProperty("RELEASE_KEY_ALIAS")
                .orElse(providers.environmentVariable("RELEASE_KEY_ALIAS"))
            val keyPasswordValue = providers.gradleProperty("RELEASE_KEY_PASSWORD")
                .orElse(providers.environmentVariable("RELEASE_KEY_PASSWORD"))

            if (storeFilePath.isPresent && storePasswordValue.isPresent &&
                keyAliasValue.isPresent && keyPasswordValue.isPresent
            ) {
                storeFile = file(storeFilePath.get())
                storePassword = storePasswordValue.get()
                keyAlias = keyAliasValue.get()
                keyPassword = keyPasswordValue.get()
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Three ways a release build can be signed, in order of preference:
            //
            //  1. The real release key, when all four RELEASE_* values are supplied.
            //  2. No key at all, with -PunsignedRelease=true: the output is
            //     app-release-unsigned.apk, meant for scripts/signing/sign-release.sh, which
            //     signs offline and refuses to produce anything signed with the debug key.
            //  3. The committed debug key, as before, so a plain `assembleRelease` with zero
            //     secrets still builds. That APK is *not* distributable — the debug key is
            //     public — so the build says so in two places a human will see: a warning in
            //     the Gradle log and "-devsigned" appended to versionName.
            //
            // The suffix is a label, not a guard. The guards are the deny-lists in
            // scripts/signing/sign-release.sh and .github/scripts/verify-apk-signer.sh.
            val releaseSigning = signingConfigs.getByName("release")
            val unsignedRelease = findProperty("unsignedRelease")?.toString() == "true"
            when {
                releaseSigning.storeFile != null -> signingConfig = releaseSigning
                unsignedRelease -> {
                    signingConfig = null
                    logger.lifecycle("release: building UNSIGNED (-PunsignedRelease=true).")
                }
                else -> {
                    signingConfig = signingConfigs.getByName("debug")
                    versionNameSuffix = "-devsigned"
                    logger.warn(
                        "WARNING: release is being signed with the committed DEBUG key " +
                            "(no RELEASE_* configuration). versionName gets the suffix " +
                            "\"-devsigned\". This APK must not be distributed."
                    )
                }
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        // Lint has been configured and never invoked since this project started, so it starts
        // from a baseline: every finding that exists today is recorded and silenced, and only
        // what arrives after this commit is reported. Delete lint-baseline.xml and re-run to
        // see the debt; shrinking it is separate work from not adding to it.
        baseline = file("lint-baseline.xml")
    }

    composeCompiler {
        stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose-stability.conf"))
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    // Robolectric needs the merged resources and the real AndroidManifest to inflate a
    // Compose hierarchy, and Roborazzi needs the native graphics mode to get pixels out
    // of it. Without both, every screenshot test would render an empty bitmap.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

/**
 * Record mode writes new baselines; every other run compares against the committed ones and
 * fails on a difference. It used to be recording unconditionally, which meant the screenshot
 * tests wrote a fresh picture on every run and compared it to nothing — they could not fail on
 * a visual regression, and did not: two layout defects on the feedback screen passed a green
 * suite and were found on a photograph of a phone.
 *
 * Re-record with `./gradlew testDebugUnitTest -Proborazzi.record`, then look at the diff in
 * `git status` before committing it. That review by eye is the only thing standing between a
 * baseline and a regression blessed as the new truth.
 */
val recordingScreenshots = providers.gradleProperty("roborazzi.record").isPresent

/**
 * The benchmark reads its endpoint, key and model list from the environment and skips
 * itself when they are absent, so ordinary builds never touch the network. Values are
 * forwarded through providers so the configuration cache stays valid.
 */
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.graphicsMode", "NATIVE")
    // Roborazzi is used without its Gradle plugin, so the modes it would otherwise derive from
    // its own tasks have to be set here.
    systemProperty("roborazzi.test.record", recordingScreenshots.toString())
    systemProperty("roborazzi.test.verify", (!recordingScreenshots).toString())
    // The baselines are neither sources nor test resources, so nothing else tells Gradle that
    // editing one should re-run the comparison. Without this an edited picture is UP-TO-DATE
    // and stays green — a quieter version of the same defect.
    inputs.dir(layout.projectDirectory.dir("src/test/screenshots"))
        .withPropertyName("screenshotBaselines")
    // ReleaseReflectionContractTest reads the keep rules, the contract and this build file as
    // data. None of them is a source or a test resource, so without declaring them the test task
    // is UP-TO-DATE after a keep rule is deleted and the guard stays green while the release
    // build is already broken. Measured: removing the FeedbackPayload rule left the guard
    // unexecuted and reported success.
    inputs.file(layout.projectDirectory.file("proguard-rules.pro"))
        .withPropertyName("keepRules")
    inputs.file(layout.projectDirectory.file("release-reflection-contract.txt"))
        .withPropertyName("reflectionContract")
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("buildScript")
    // The same guard reads the main sources as text, to see which files hand a type to Gson and
    // whether a contract type carries @SerializedName. Compilation inputs do not cover reading a
    // file as data: adding an annotation to FeedbackPayload was served FROM-CACHE and the guard
    // never ran.
    inputs.dir(layout.projectDirectory.dir("src/main/java"))
        .withPropertyName("mainSourcesAsData")
    // ManifestContractTest reads the manifest and the accessibility configuration as text. They
    // do reach the test JVM a second way -- merged into the resources -- but that route is a
    // transitive one through another task's output, and both false-green defects this build has
    // recorded were a guard reading a file Gradle did not know it read. Say it directly instead.
    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
        .withPropertyName("manifestAsData")
    inputs.dir(layout.projectDirectory.dir("src/main/res/xml"))
        .withPropertyName("resXmlAsData")
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
    listOf("OPENAI_ENDPOINT", "OPENAI_API_KEY", "OPENAI_MODEL", "BENCH_MODELS").forEach { key ->
        val value = providers.environmentVariable(key)
        if (value.isPresent) environment(key, value.get())
    }

    // Environment variables are not task inputs, so a benchmark run with a changed model
    // list would otherwise be skipped as UP-TO-DATE and silently report the previous
    // run's numbers. Declaring them as inputs would hash the API key into Gradle's task
    // history on disk, so force re-execution instead whenever the benchmark is wired up.
    if (providers.environmentVariable("OPENAI_ENDPOINT").isPresent) {
        outputs.upToDateWhen { false }
    }

    // CI refuses a test result it did not watch execute ("Prove the suite ran on this commit"),
    // and setup-gradle restores the default branch's build cache into every branch build. A pull
    // request that changes no declared input of this task -- documentation, firebase.json, a
    // rules file -- is therefore served FROM-CACHE and fails a check that has nothing to do with
    // the change. The refusal is right; what was wrong is that it was routinely reachable. Make
    // it unreachable at the source: in CI this task is neither cacheable nor ever up to date, so
    // the only way the log can say FROM-CACHE again is if this block stopped applying.
    if (providers.environmentVariable("CI").isPresent) {
        outputs.cacheIf { false }
        outputs.upToDateWhen { false }
    }
}

/**
 * The only check in this build that can fail for the real reason.
 *
 * Gson reflects over field names, so a reflectively serialised field whose class is not under a
 * keep rule is renamed by R8 and the app writes `{"a":…,"b":…}` on the wire. Unit tests run on
 * the JVM against un-shrunk classes, so no test can see it — and none did: `FeedbackPayload` was
 * added outside the two packages `proguard-rules.pro` enumerates and a release build obfuscated
 * its six fields to `a`…`f` while the whole suite stayed green.
 *
 * `ReleaseReflectionContractTest` checks the cause on the JVM, that a field-preserving keep rule
 * exists for every type on the contract. This checks the effect, in R8's own `mapping.txt`, which
 * is the only place the answer actually is. Note what green looks like: R8 records renames only,
 * so a kept field has no line at all and it is the *presence* of a rename that fails here.
 *
 * The class name is deliberately not checked. Gson resolves fields off the runtime class and
 * never reads its name, so a renamed class with kept fields is correct and must not fail.
 */
val reflectionContract = layout.projectDirectory.file("release-reflection-contract.txt")
val releaseMapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")

val verifyReleaseReflectionContract = tasks.register("verifyReleaseReflectionContract") {
    group = "verification"
    description = "Fails if R8 renamed a field whose name is part of a wire contract."
    dependsOn("minifyReleaseWithR8")
    inputs.file(reflectionContract).withPropertyName("reflectionContract")
    inputs.file(releaseMapping).withPropertyName("releaseMapping")

    val contractFile = reflectionContract
    val mappingFile = releaseMapping
    doLast {
        val mapping = mappingFile.get().asFile
        val types = contractFile.asFile.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
        if (types.isEmpty()) {
            throw GradleException(
                "release-reflection-contract.txt lists no types. Either the file was emptied, " +
                    "in which case this check now proves nothing, or every reflective type is " +
                    "annotated \u2014 say which in the file rather than leaving it blank."
            )
        }
        val lines = mapping.readLines()
        val problems = mutableListOf<String>()
        for (type in types) {
            val header = lines.indexOfFirst { it.startsWith("$type -> ") && it.endsWith(":") }
            if (header < 0) {
                problems += "$type is not in mapping.txt at all. It was shrunk away, moved or " +
                    "renamed, and whatever keep rule names it now guards nothing."
                continue
            }
            val renamed = mutableListOf<String>()
            var i = header + 1
            while (i < lines.size && (lines[i].startsWith(" ") || lines[i].startsWith("#"))) {
                val line = lines[i].trim()
                i++
                // Comments, methods (which have an argument list) and the line-number tables
                // that start with a digit are not field renames.
                if (line.startsWith("#") || line.contains("(")) continue
                if (line.firstOrNull()?.isDigit() == true) continue
                val parts = line.split(" -> ")
                if (parts.size != 2) continue
                val from = parts[0].substringAfterLast(' ')
                val to = parts[1].trim()
                if (from != to) renamed += "$from -> $to"
            }
            if (renamed.isNotEmpty()) {
                problems += "$type had field names obfuscated: ${renamed.joinToString(", ")}"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "R8 did not preserve field names that are part of a wire contract:\n  " +
                    problems.joinToString("\n  ") +
                    "\n\nThis release would write obfuscated keys and no unit test can see it. " +
                    "Add a keep rule of the form -keep class <type> { <fields>; } in " +
                    "proguard-rules.pro. Do not widen an existing rule to a whole package."
            )
        }
    }
}

// A finalizer rather than a dependency, so the check runs against the artifact that was actually
// produced; a failing finalizer still fails the build.
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    finalizedBy(verifyReleaseReflectionContract)
}

/**
 * Unit tests run against the debug variant only. The Compose UI tests (createComposeRule)
 * launch androidx.activity.ComponentActivity, which only exists in the merged manifest
 * through `debugImplementation(ui-test-manifest)`; it must never be part of a release
 * artifact, so the release-variant unit-test task cannot resolve the activity and has
 * failed for every Compose test since PR #1. CI has always run `testDebugUnitTest`; this
 * makes `./gradlew test` say the same thing.
 */
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // ProcessLifecycleOwner: the app coming to the foreground is a refresh trigger.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.gson)
    debugImplementation(libs.okhttp.logging)

    // DataStore
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Sign-in and the outbox worker
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.androidx.work.runtime)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.okhttp.mockwebserver)
    // The app refuses plain HTTP, so the verifier can only be exercised over TLS.
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.work.testing)
}
