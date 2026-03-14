import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    // Плагин версии 2.x для поддержки новых версий IDE (2024-2025)
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

// Устанавливаем единую версию Java для всего проекта
kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    intellijPlatform {
        // Подключение локальной IDE. Этого достаточно, чтобы runIde знал, что запускать.
        local(file("D:/Android Studio"))

        // Явно подключаем плагины, которые содержат ddmlib и Java API
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("com.intellij.java")

        // Инструментарий для сборки плагина
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
        // Передача системных свойств для корректного запуска в режиме Android Studio
        systemProperty("idea.platform.prefix", "AndroidStudio")
        maxHeapSize = "2g"
    }

    withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }

    // ОТКЛЮЧАЕМ создание индекса поиска, чтобы сборка шла быстрее и без ошибок GUI
    buildSearchableOptions {
        enabled = false
    }
}
