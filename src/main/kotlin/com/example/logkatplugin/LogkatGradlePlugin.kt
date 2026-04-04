package com.example.logkatplugin

import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class LogkatGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            androidComponents?.onVariants { variant ->
                val configFile = File(project.projectDir, ".idea/logkat_trace_settings.txt")
                val configContent = if (configFile.exists()) configFile.readText() else ""

                variant.instrumentation.transformClassesWith(
                    LogkatAsmFactory::class.java,
                    InstrumentationScope.ALL
                ) { params ->
                    params.configContent.set(configContent)
                }
                
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }
        }
        
        project.plugins.withId("com.android.library") {
            val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            androidComponents?.onVariants { variant ->
                val configFile = File(project.projectDir, ".idea/logkat_trace_settings.txt")
                val configContent = if (configFile.exists()) configFile.readText() else ""

                variant.instrumentation.transformClassesWith(
                    LogkatAsmFactory::class.java,
                    InstrumentationScope.ALL
                ) { params ->
                    params.configContent.set(configContent)
                }

                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }
        }
    }
}
