package com.example.logkatplugin

import com.android.build.api.instrumentation.*
import org.objectweb.asm.*

/**
 * Основная фабрика для внедрения трассировки Logkat.
 */
abstract class LogkatAsmFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        return LogkatClassTransformer(nextClassVisitor, classContext.currentClassData.className)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        return !name.startsWith("android.") &&
               !name.startsWith("com.google.") &&
               !name.contains("LogkatTracer") &&
               !name.contains(".R")
    }
}

class LogkatClassTransformer(cv: ClassVisitor, private val className: String) : ClassVisitor(Opcodes.ASM9, cv) {
    private var sourceFile: String = "Unknown"

    override fun visitSource(source: String?, debug: String?) {
        super.visitSource(source, debug)
        if (source != null) sourceFile = source
    }

    override fun visitMethod(access: Int, name: String, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return LogkatMethodTransformer(api, mv, className, sourceFile, name)
    }
}

class LogkatMethodTransformer(
    api: Int,
    mv: MethodVisitor,
    private val className: String,
    private val sourceFile: String,
    private val methodName: String
) : MethodVisitor(api, mv) {

    private var currentLine: Int = -1

    override fun visitLineNumber(line: Int, start: Label?) {
        super.visitLineNumber(line, start)
        currentLine = line
    }

    override fun visitMethodInsn(opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean) {
        // Вставляем трассировку ПЕРЕД вызовами других методов
        if (name != "<init>" && owner?.contains("LogkatTracer") == false && currentLine != -1) {
            insertTrace("CALL: $name")
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    private fun insertTrace(info: String) {
        super.visitLdcInsn(sourceFile)
        super.visitIntInsn(Opcodes.SIPUSH, currentLine)
        super.visitLdcInsn(info)
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "LogkatTracer",
            "trace",
            "(Ljava/lang/String;ILjava/lang/String;)V",
            false
        )
    }
}
