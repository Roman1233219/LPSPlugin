package com.example.lpsplugin

import com.android.build.api.instrumentation.*
import org.objectweb.asm.*
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

interface LPSParameters : InstrumentationParameters {
    @get:Input
    val configContent: Property<String>
}

abstract class LPSAsmFactory : AsmClassVisitorFactory<LPSParameters> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        val config = parseConfig(parameters.get().configContent.getOrElse(""))
        return LPSClassTransformer(nextClassVisitor, classContext.currentClassData.className, config)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        if (name.startsWith("android.") || name.startsWith("com.google.") || 
            name.contains("LPSTracer") || name.contains(".R")) return false
            
        return true
    }
    
    private fun parseConfig(content: String): Map<String, String> {
        if (content.isEmpty()) return emptyMap()
        return content.lines()
            .filter { it.contains("=") }
            .associate { 
                val parts = it.split("=", limit = 2)
                parts[0].trim() to parts[1].trim()
            }
    }
}

class LPSClassTransformer(
    cv: ClassVisitor, 
    private val className: String,
    private val config: Map<String, String>
) : ClassVisitor(Opcodes.ASM9, cv) {
    
    private var sourceFile: String = "Unknown"
    private val level = config["LEVEL"] ?: "MINIMAL"

    override fun visitSource(source: String?, debug: String?) {
        super.visitSource(source, debug)
        if (source != null) sourceFile = source
    }

    override fun visitMethod(access: Int, name: String, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        
        val shouldTraceByLevel = checkAccessByLevel(access, name, level)
        return LPSMethodTransformer(api, mv, className, sourceFile, name, shouldTraceByLevel, level == "SELECTIVE")
    }
    
    private fun checkAccessByLevel(access: Int, name: String, level: String): Boolean {
        val isPublic = (access and Opcodes.ACC_PUBLIC) != 0
        val isProtected = (access and Opcodes.ACC_PROTECTED) != 0
        val isPrivate = (access and Opcodes.ACC_PRIVATE) != 0
        val isPackagePrivate = !isPublic && !isProtected && !isPrivate
        
        val isConstructor = name == "<init>" || name == "<clinit>"
        val isAccessor = name.startsWith("get") || name.startsWith("set") || name.startsWith("is")
        val isStandard = name == "toString" || name == "hashCode" || name == "equals"
        
        return when (level) {
            "MINIMAL" -> isPublic && !isConstructor && !isAccessor
            "BASIC" -> (isPublic || isProtected) && !isConstructor && !isAccessor
            "STANDARD" -> !isPrivate && !isConstructor && !isAccessor
            "ADVANCED" -> !isStandard
            "FULL" -> true
            "SELECTIVE" -> false
            else -> isPublic
        }
    }
}

class LPSMethodTransformer(
    api: Int,
    mv: MethodVisitor,
    private val className: String,
    private val sourceFile: String,
    private val methodName: String,
    private val shouldTraceByLevel: Boolean,
    private val isSelective: Boolean
) : MethodVisitor(api, mv) {

    private var currentLine: Int = -1
    private var isMarkedWithTrace = false

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
        if (descriptor?.contains("Trace") == true) {
            isMarkedWithTrace = true
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitLineNumber(line: Int, start: Label?) {
        super.visitLineNumber(line, start)
        currentLine = line
    }

    override fun visitCode() {
        super.visitCode()
        if (shouldTraceByLevel || (isSelective && isMarkedWithTrace)) {
            insertTrace("ENTER: $methodName")
        }
    }

    private fun insertTrace(info: String) {
        super.visitLdcInsn(sourceFile)
        super.visitIntInsn(Opcodes.SIPUSH, if (currentLine != -1) currentLine else 0)
        super.visitLdcInsn(info)
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "LPSTracer",
            "trace",
            "(Ljava/lang/String;ILjava/lang/String;)V",
            false
        )
    }
}
