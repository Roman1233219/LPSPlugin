package com.example.logkatplugin

import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class LogkatGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            androidComponents?.onVariants { variant ->
                variant.instrumentation.transformClassesWith(
                    LogkatAsmFactory::class.java,
                    InstrumentationScope.ALL
                ) {}
                // КРИТИЧНО: Используем режим автоматического пересчета фреймов и стека
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }
        }
        
        project.plugins.withId("com.android.library") {
            val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            androidComponents?.onVariants { variant ->
                variant.instrumentation.transformClassesWith(
                    LogkatAsmFactory::class.java,
                    InstrumentationScope.ALL
                ) {}
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }
        }
    }
}
