import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.0"
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
}

group = "io.github.Roman1233219"
version = "2.0.0"

repositories {
    mavenCentral()
    google()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

gradlePlugin {
    website.set("https://github.com/Roman1233219/LPSPlugin")
    vcsUrl.set("https://github.com/Roman1233219/LPSPlugin")
    plugins {
        create("lpsPlugin") {
            id = "io.github.Roman1233219.lps"
            implementationClass = "com.example.lpsplugin.LPSGradlePlugin"
            displayName = "LPS Instrumentation Plugin"
            description = "Bytecode instrumentation for LPS Process Logger"
            tags.set(listOf("android", "logging", "tracing"))
        }
    }
}

dependencies {
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    compileOnly("com.android.tools.build:gradle-api:8.1.0")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
