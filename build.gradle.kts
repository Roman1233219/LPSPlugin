import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
}

group = "com.example.logkatplugin"
version = "2.0.0"

repositories {
    mavenCentral()
    google()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Библиотеки для работы с байт-кодом (ASM)
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")

    // Android Gradle Plugin API для инструментации (нужно для компиляции LogkatAsmFactory)
    compileOnly("com.android.tools.build:gradle-api:8.1.0")
    compileOnly("com.android.tools.build:gradle:8.1.0")

    intellijPlatform {
        local(file("D:/Android Studio"))
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        id.set("com.example.logkatplugin")
        name.set("Logkat Process Logger")

        ideaVersion {
            sinceBuild.set("232")
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
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }

    buildSearchableOptions {
        enabled = false
    }
}
