plugins {
    // Только объявляем версии сторонних плагинов, не применяя их к корню
    id("org.jetbrains.intellij.platform") version "2.2.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    id("com.gradle.plugin-publish") version "1.3.0" apply false
}

allprojects {
    group = "io.github.Roman1233219"
    version = "2.0.0"

    repositories {
        mavenCentral()
        google()
    }
}
