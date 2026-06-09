plugins {
    kotlin("jvm") version "2.4.0"
    application
}

repositories { mavenCentral() }

dependencies {
    // Same version the Android app loads, so the on-disk graph format matches.
    implementation("com.graphhopper:graphhopper-core:7.0") {
        // logback misbehaves; use slf4j-simple for readable import progress.
        exclude(group = "ch.qos.logback", module = "logback-classic")
    }
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("MainKt")
    // Run from the repo root so relative pbf/graph paths resolve there.
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir.parentFile.parentFile // scripts/graphtool -> scripts -> repo root
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
