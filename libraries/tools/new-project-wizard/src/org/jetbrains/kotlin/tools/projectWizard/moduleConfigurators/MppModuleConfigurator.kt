/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.tools.projectWizard.moduleConfigurators

import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.tools.projectWizard.KotlinNewProjectWizardBundle
import org.jetbrains.kotlin.tools.projectWizard.core.*
import org.jetbrains.kotlin.tools.projectWizard.core.entity.properties.ModuleConfiguratorProperty
import org.jetbrains.kotlin.tools.projectWizard.ir.buildsystem.KotlinBuildSystemPluginIR
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.ModuleSubType
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.ModulesToIrConversionData
import org.jetbrains.kotlin.tools.projectWizard.plugins.projectPath
import org.jetbrains.kotlin.tools.projectWizard.plugins.templates.TemplatesPlugin
import org.jetbrains.kotlin.tools.projectWizard.settings.JavaPackage
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.Module
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.ModuleKind
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.SourcesetType
import org.jetbrains.kotlin.tools.projectWizard.templates.FileDescriptor
import org.jetbrains.kotlin.tools.projectWizard.templates.FileTemplate
import org.jetbrains.kotlin.tools.projectWizard.templates.FileTemplateDescriptorWithPath
import org.jetbrains.kotlin.tools.projectWizard.templates.FileTextDescriptor
import java.nio.file.Path

object MppModuleConfigurator : ModuleConfigurator,
    ModuleConfiguratorWithSettings,
    ModuleConfiguratorSettings(),
    ModuleConfiguratorProperties,
    ModuleConfiguratorWithProperties {

    override val moduleKind = ModuleKind.multiplatform

    @NonNls
    override val suggestedModuleName = "shared"

    @NonNls
    override val id = "multiplatform"
    override val text = KotlinNewProjectWizardBundle.message("module.configurator.mpp")
    override val canContainSubModules = true

    override fun createKotlinPluginIR(configurationData: ModulesToIrConversionData, module: Module): KotlinBuildSystemPluginIR? =
        KotlinBuildSystemPluginIR(
            KotlinBuildSystemPluginIR.Type.multiplatform,
            version = configurationData.kotlinVersion
        )

    val mppSources by listProperty<MppSources>()

    override fun getConfiguratorProperties(): List<ModuleConfiguratorProperty<*>> =
        listOf(mppSources)

    override fun Writer.runArbitraryTask(
        configurationData: ModulesToIrConversionData,
        module: Module,
        modulePath: Path,
    ): TaskResult<Unit> = compute {
        createMppFiles(module, modulePath).ensure()
        createSimpleFiles(module, modulePath).ensure()
    }

    private fun Writer.createMppFiles(
        module: Module,
        modulePath: Path
    ): TaskResult<Unit> = inContextOfModuleConfigurator(module) {
        val mppFiles = mppSources.reference.propertyValue.flatMap { it.mppFiles }
            .distinctBy { it.fileNameWithPackage } // todo merge files with the same name here
        mppFiles.mapSequenceIgnore { file ->
            module.subModules.mapSequenceIgnore mapTargets@{ target ->
                val moduleSubType = //TODO handle for non-simple target configurator
                    target.configurator.safeAs<SimpleTargetConfigurator>()?.moduleSubType ?: return@mapTargets UNIT_SUCCESS
                val path = pathForFileInTarget(modulePath, module, file.javaPackage, file.filename, target, SourcesetType.main)
                val fileTemplate = FileTemplate(
                    FileTextDescriptor(file.printForModuleSubType(moduleSubType), path),
                    projectPath,
                )
                TemplatesPlugin.fileTemplatesToRender.addValues(fileTemplate)
            }
        }
    }

    private fun Writer.createSimpleFiles(
        module: Module,
        modulePath: Path
    ): TaskResult<Unit> = inContextOfModuleConfigurator(module) {
        val mppFiles = mppSources.reference.propertyValue
        module.subModules.mapSequenceIgnore mapTargets@{ target ->
            val moduleSubType = //TODO handle for non-simple target configurator
                target.configurator.safeAs<SimpleTargetConfigurator>()?.moduleSubType ?: return@mapTargets UNIT_SUCCESS
            val files = mppFiles.flatMap { it.getFilesFor(moduleSubType) }.distinctBy { it.uniqueIdentifier }
            val fileTemplates = files.map { file ->
                FileTemplate(
                    file.fileDescriptor,
                    projectPath / pathForFileInTarget(modulePath, module, file.javaPackage, file.filename, target, file.type),
                )
            }
            TemplatesPlugin.fileTemplatesToRender.addValues(fileTemplates)
        }
    }

    private fun pathForFileInTarget(
        mppModulePath: Path,
        mppModule: Module,
        javaPackage: JavaPackage?,
        filename: String,
        target: Module,
        sourcesetType: SourcesetType,
    ) = mppModulePath /
            Defaults.SRC_DIR /
            "${target.name}${sourcesetType.name.capitalize()}" /
            mppModule.configurator.kotlinDirectoryName /
            javaPackage?.asPath() /
            filename
}


@DslMarker
annotation class ExpectFileDSL

data class MppFile(
    @NonNls val filename: String,
    @NonNls val javaPackage: JavaPackage? = null,
    val declarations: List<MppDeclaration>
) {

    val fileNameWithPackage
        get() = javaPackage?.let { "${it.asCodePackage()}." }.orEmpty() + filename

    fun printForModuleSubType(moduleSubType: ModuleSubType): String = buildString {
        javaPackage?.let { appendLine("package ${it.asCodePackage()}"); appendLine() }
        if (moduleSubType == ModuleSubType.common) {
            declarations.joinTo(this, separator = "\n\n") { it.printExpect() }
        } else {
            printImports(declarations.flatMap { it.importsFor(moduleSubType) })
            declarations.joinTo(this, separator = "\n\n") { it.printActualFor(moduleSubType) }
        }
    }.trim()

    private fun StringBuilder.printImports(imports: List<String>) {
        if (imports.isEmpty()) return
        imports.distinct().joinTo(this, separator = "\n", prefix = "\n", postfix = "\n\n") { import ->
            "import $import"
        }
    }

    @ExpectFileDSL
    class Builder(private val filename: String) {
        var `package`: String? = null
        private val declarations = mutableListOf<MppDeclaration>()

        fun function(signature: String, init: MppFunction.Builder.() -> Unit = {}) {
            declarations += MppFunction.Builder(signature).apply(init).build()
        }

        fun `class`(name: String, init: MppClass.Builder.() -> Unit = {}) {
            declarations += MppClass.Builder(name).apply(init).build()
        }

        fun build() = MppFile(filename, `package`?.let(::JavaPackage), declarations)
    }
}


@ExpectFileDSL
class MppSources(val mppFiles: List<MppFile>, val simpleFiles: List<SimpleFiles>) {

    fun getFilesFor(moduleSubType: ModuleSubType): List<SimpleFile> =
        simpleFiles.filter { moduleSubType in it.moduleSubTypes }.flatMap { it.files }

    class Builder {
        private val mppFiles = mutableListOf<MppFile>()
        private val simpleFiles = mutableListOf<SimpleFiles>()

        fun mppFile(filename: String, init: MppFile.Builder.() -> Unit) {
            mppFiles += MppFile.Builder(filename).apply(init).build()
        }

        fun filesFor(vararg moduleSubTypes: ModuleSubType, init: SimpleFiles.Builder.() -> Unit) {
            simpleFiles += SimpleFiles.Builder(moduleSubTypes.toList()).apply(init).build()
        }

        fun build() = MppSources(mppFiles, simpleFiles)
    }
}


data class SimpleFiles(val moduleSubTypes: List<ModuleSubType>, val files: List<SimpleFile>) {
    class Builder(private val moduleSubTypes: List<ModuleSubType>) {
        private val files = mutableListOf<SimpleFile>()

        fun file(fileDescriptor: FileDescriptor, filename: String, type: SourcesetType, init: SimpleFile.Builder.() -> Unit = {}) {
            files += SimpleFile.Builder(fileDescriptor, filename, type).apply(init).build()
        }

        fun build() = SimpleFiles(moduleSubTypes, files)
    }
}

data class SimpleFile(val fileDescriptor: FileDescriptor, val javaPackage: JavaPackage?, val filename: String, val type: SourcesetType) {
    val uniqueIdentifier get() = "${type}/${javaPackage}/${filename}"

    class Builder(private val fileDescriptor: FileDescriptor, private val filename: String, private val type: SourcesetType) {
        var javaPackage: JavaPackage? = null

        fun build() = SimpleFile(fileDescriptor, javaPackage, filename, type)
    }
}


fun mppSources(init: MppSources.Builder.() -> Unit): MppSources =
    MppSources.Builder().apply(init).build()

sealed class MppDeclaration {
    @Suppress("SpellCheckingInspection")
    abstract val actuals: Actuals

    abstract fun printExpect(): String

    abstract fun printActualFor(moduleSubType: ModuleSubType): String

    fun importsFor(moduleSubType: ModuleSubType): List<String> = actuals.getBodyFor(moduleSubType).imports

    @ExpectFileDSL
    abstract class Builder {
        private var defaultActualDeclarationBody = DefaultActualDeclarationBody(text = "", imports = emptyList())

        @Suppress("SpellCheckingInspection")
        private val actuals = mutableMapOf<ModuleSubType, ActualDeclarationBody>()

        fun default(defaultText: String, init: DefaultActualDeclarationBody.Builder.() -> Unit = {}) {
            defaultActualDeclarationBody = DefaultActualDeclarationBody.Builder(defaultText).also(init).build()
        }

        fun actualFor(vararg moduleSubTypes: ModuleSubType, actualBody: String, init: ActualDeclarationBody.Builder.() -> Unit = {}) {
            moduleSubTypes.forEach { moduleSubType ->
                actuals[moduleSubType] = ActualDeclarationBody.Builder(actualBody).apply(init).build()
            }
        }

        protected fun buildActuals() = Actuals(defaultActualDeclarationBody, actuals)
    }
}

data class MppFunction(
    val signature: String,
    @Suppress("SpellCheckingInspection")
    override val actuals: Actuals,
) : MppDeclaration() {
    override fun printExpect(): String =
        "expect fun $signature"

    override fun printActualFor(moduleSubType: ModuleSubType): String {
        val body = actuals.getBodyFor(moduleSubType)
        return buildString {
            appendLine("actual fun $signature {")
            appendLine(body.text.indented(4))
            append("}")
        }
    }

    @ExpectFileDSL
    class Builder(private val signature: String) : MppDeclaration.Builder() {
        fun build(): MppFunction = MppFunction(
            signature,
            buildActuals(),
        )
    }
}

data class MppClass(
    val name: String,
    val expectBody: String?,
    @Suppress("SpellCheckingInspection")
    override val actuals: Actuals,
) : MppDeclaration() {
    override fun printExpect(): String = buildString {
        append("expect class $name()")
        expectBody?.let { body ->
            appendLine(" {")
            appendLine(body.indented(4))
            append("}")
        }
    }

    override fun printActualFor(moduleSubType: ModuleSubType): String {
        val body = actuals.getBodyFor(moduleSubType)
        return buildString {
            appendLine("actual class $name actual constructor() {")
            appendLine(body.text.indented(4))
            append("}")
        }
    }

    @ExpectFileDSL
    class Builder(private val name: String) : MppDeclaration.Builder() {
        var expectBody: String? = null

        fun build(): MppClass = MppClass(
            name,
            expectBody,
            buildActuals(),
        )
    }
}


private fun String.indented(indentValue: Int): String {
    val indent = " ".repeat(indentValue)
    return split('\n').joinToString(separator = "\n") { line -> indent + line.trim() }
}

@Suppress("SpellCheckingInspection")
data class Actuals(
    val defaultActualDeclarationBody: DefaultActualDeclarationBody,
    val actuals: Map<ModuleSubType, ActualDeclarationBody>,
) {
    fun getBodyFor(moduleSubType: ModuleSubType): DeclarationBody =
        actuals[moduleSubType] ?: defaultActualDeclarationBody
}

sealed class DeclarationBody {
    abstract val text: String
    abstract val imports: List<String>

    abstract class Builder {
        protected val imports = mutableListOf<String>()

        fun import(import: String) {
            imports += import
        }
    }
}

data class ActualDeclarationBody(override val text: String, override val imports: List<String>) : DeclarationBody() {
    class Builder(private val text: String) : DeclarationBody.Builder() {
        fun build() = ActualDeclarationBody(text, imports)
    }
}

data class DefaultActualDeclarationBody(override val text: String, override val imports: List<String>) : DeclarationBody() {
    class Builder(private val text: String) : DeclarationBody.Builder() {
        fun build() = DefaultActualDeclarationBody(text, imports)
    }
}