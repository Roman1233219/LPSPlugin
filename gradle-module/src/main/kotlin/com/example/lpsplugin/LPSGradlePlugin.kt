package com.example.lpsplugin

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import com.android.build.api.instrumentation.InstrumentationScope

class LPSGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            androidComponents?.onVariants { variant ->
                val configFile = File(project.projectDir, ".idea/lps_trace_settings.txt")
                val configContent = if (configFile.exists()) configFile.readText() else ""

                variant.instrumentation.transformClassesWith(
                    LPSAsmFactory::class.java,
                    InstrumentationScope.ALL
                ) { params ->
                    params.configContent.set(configContent)
                }
            }
        }
        
        project.plugins.withId("com.android.library") {
            val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            androidComponents?.onVariants { variant ->
                val configFile = File(project.projectDir, ".idea/lps_trace_settings.txt")
                val configContent = if (configFile.exists()) configFile.readText() else ""

                variant.instrumentation.transformClassesWith(
                    LPSAsmFactory::class.java,
                    InstrumentationScope.ALL
                ) { params ->
                    params.configContent.set(configContent)
                }
            }
        }
    }
}
