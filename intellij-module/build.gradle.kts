import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
}

group = "io.github.Roman1233219"
version = "2.0.0"

repositories {
    mavenCentral()
    google()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    intellijPlatform {
        local(file("C:/Program Files/Android/Android Studio"))
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        id.set("io.github.Roman1233219.lpsplugin")
        name.set("LPS Process Logger")
        ideaVersion {
            sinceBuild.set("253")
            untilBuild.set("253.*")
        }
    }
}

tasks {
    runIde {
        systemProperty("idea.platform.prefix", "AndroidStudio")
        maxHeapSize = "2g"
    }

    withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }

    buildSearchableOptions {
        enabled = false
    }
}
