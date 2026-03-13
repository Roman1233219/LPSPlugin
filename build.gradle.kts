plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
}

group = "com.example.logkatplugin"
version = "2.0.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

intellij {
    // ВНИМАНИЕ: Проверьте путь к установленной Android Studio на вашем компьютере!
    // Обычно это "C:/Program Files/Android/Android Studio" или путь, куда вы распаковали архив.
    localPath.set("C:/Program Files/Android/Android Studio")
    plugins.set(listOf("android", "com.intellij.java"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("253.*")
    }

    signPlugin { enabled = false }
    publishPlugin { enabled = false }
    
    // Отключаем задачу buildSearchableOptions, так как она часто падает при использовании localPath
    buildSearchableOptions {
        enabled = false
    }

    runIde {
        maxHeapSize = "2g" // Увеличиваем кучу для отладочной IDE
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xskip-metadata-version-check")
        }
    }
}
