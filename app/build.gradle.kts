plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.ghostroute.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ghostroute.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase1"
        // No test runner that pulls GMS; keep deps minimal.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // GhostRoute ships as a sideloaded debug build (no Play Store on GrapheneOS),
            // so this IS the build people drive with. A debuggable build makes ART skip
            // JIT optimization, which slows GraphHopper's routing hot loops ~30x (a route
            // took ~2 s instead of ~55 ms). So we ship NON-debuggable for full speed (no
            // R8/proguard risk to GraphHopper's reflection, unlike a minified release).
            //
            // The catch: pushing the big data files (graph/geocoder/basemap) into the app's
            // internal storage needs `run-as`, which ONLY works on a debuggable build (no
            // adb root on a GrapheneOS user build; app+adb get isolated FUSE views of
            // external Android/data). So data pushes use the dance: build a one-off
            // debuggable variant with `-PdataPush`, push, then reinstall the normal
            // (non-debuggable) build — internal files persist across same-key reinstalls.
            isDebuggable = project.hasProperty("dataPush")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // Drop duplicate license/notice metadata that several of GraphHopper's
            // transitive jars each ship (harmless text files, not code).
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Phase 2: local camera DB (Room) + periodic data sync (WorkManager).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    // Room's processor bundles a kotlin-metadata-jvm that lags Kotlin 2.4.0 (reads
    // up to metadata 2.3.0). Force the matching version onto the kapt classpath so
    // it can parse 2.4.0 metadata. Remove once Room ships 2.4.0 metadata support.
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
    implementation(libs.androidx.work.runtime.ktx)

    // Phase 3: on-device offline routing. logback-classic is a JVM logging
    // backend that misbehaves on Android — exclude it (slf4j falls back to no-op).
    implementation(libs.graphhopper.core) {
        exclude(group = "ch.qos.logback", module = "logback-classic")
    }
    // Runtime-only shim for javax.lang.model.SourceVersion, which GraphHopper
    // uses to validate encoded-value names but which is absent on Android.
    // Prebuilt jar (javac can't compile it in-tree — the java.compiler module
    // owns that package); regenerate with scripts/build-shim.sh.
    implementation(files("libs/sourceversion-shim.jar"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Offline vector map rendering with native PMTiles support (v11+).
    implementation(libs.maplibre.android)

    // Android Auto (projected). Pure AndroidX — no Google Play Services; the phone's
    // Android Auto host does the projection. Verified GMS-clean by :app:verifyNoGms.
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
}

// ---------------------------------------------------------------------------
// Phase 0 hard rule: NO Google Play Services / Firebase / phone-home analytics.
// This task resolves the real runtime classpath and fails the build if any
// banned coordinate appears. It is wired into preBuild so every build enforces it.
// ---------------------------------------------------------------------------
val bannedGroupPrefixes = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.android.play", // play-services / billing / ads
    "com.crashlytics",
    "io.fabric",
    "com.google.android.datatransport", // firebase telemetry transport
)

val verifyNoGms by tasks.registering {
    group = "verification"
    description = "Fails the build if any Google Play Services / Firebase / analytics dependency is present."
    doLast {
        val offenders = sortedSetOf<String>()
        configurations
            .filter { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") }
            .forEach { config ->
                runCatching {
                    config.incoming.resolutionResult.allComponents.forEach { component ->
                        val id = component.id.displayName // e.g. "com.foo:bar:1.2.3"
                        if (bannedGroupPrefixes.any { id.startsWith(it) }) {
                            offenders += id
                        }
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("GhostRoute dependency audit FAILED — banned (phone-home) dependencies on the classpath:")
                    offenders.forEach { appendLine("  ✗ $it") }
                    appendLine("GhostRoute must run on GrapheneOS with no GMS. Remove these before building.")
                },
            )
        }
        logger.lifecycle("✓ GhostRoute dependency audit passed — no GMS/Firebase/analytics on the classpath.")
    }
}

tasks.named("preBuild") { dependsOn(verifyNoGms) }
