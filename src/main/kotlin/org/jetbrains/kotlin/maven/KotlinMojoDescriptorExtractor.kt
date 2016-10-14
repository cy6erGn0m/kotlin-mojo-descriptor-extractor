package org.jetbrains.kotlin.maven

import com.intellij.openapi.*
import com.intellij.openapi.util.*
import org.apache.maven.plugin.descriptor.*
import org.apache.maven.tools.plugin.*
import org.apache.maven.tools.plugin.extractor.*
import org.jetbrains.kotlin.cli.common.*
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.config.*
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.*
import org.jetbrains.kotlin.load.java.*
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.annotations.*
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.jvm.*
import org.jetbrains.kotlin.resolve.scopes.*
import org.jetbrains.kotlin.resolve.source.*
import java.io.*

class KotlinMojoDescriptorExtractor : MojoDescriptorExtractor {
    override fun execute(request: PluginToolsRequest): MutableList<MojoDescriptor> {
        return analyze(
                request.project.dependencyArtifacts.mapNotNull { it.file },
                request.project.compileSourceRoots.map { File(it.toString()) }.filter(File::exists)).toMutableList()
    }

    private fun analyze(classPath: List<File>, roots: List<File>): List<MojoDescriptor> {
        setIdeaIoUseFallback()

        val configuration = CompilerConfiguration()
        val printingMessageCollector = PrintingMessageCollector(System.err, MessageRenderer.WITHOUT_PATHS, false)
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, printingMessageCollector)
        configuration.put(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE, false)
        configuration.put(CLIConfigurationKeys.REPORT_PERF, false)

        configuration.put(CommonConfigurationKeys.LANGUAGE_FEATURE_SETTINGS, LanguageVersion.LATEST)

        configuration.put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_1_6)
        configuration.put(JVMConfigurationKeys.MODULE_NAME, JvmAbi.DEFAULT_MODULE_NAME)

        for (item in classPath) {
            configuration.add(JVMConfigurationKeys.CONTENT_ROOTS, JvmClasspathRoot(item))
        }

        roots.forEach { configuration.add(JVMConfigurationKeys.CONTENT_ROOTS, KotlinSourceRoot(it.absolutePath)) }

        val environment = KotlinCoreEnvironment.createForProduction(Disposable { }, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)

        val mc = TopDownAnalyzerFacadeForJVM.createContextWithSealedModule(environment.project, environment.getModuleName())

        val trace = CliLightClassGenerationSupport.CliBindingTrace()

        TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegrationNoIncremental(mc, environment.getSourceFiles(), trace, TopDownAnalysisMode.TopLevelDeclarations, JvmPackagePartProvider(environment))

        val mojoAnnotation = FqName("org.apache.maven.plugins.annotations.Mojo")

        return trace.getKeys(BindingContext.FQNAME_TO_CLASS_DESCRIPTOR)
                .asSequence()
                .mapNotNull { trace.get(BindingContext.FQNAME_TO_CLASS_DESCRIPTOR, it) }
                .filter { it.annotations.hasAnnotation(mojoAnnotation) }
                .mapNotNull { classDescriptor ->
                    MojoDescriptor().apply {
                        val annotation = classDescriptor.annotations.findAnnotation(mojoAnnotation) ?: return@mapNotNull null

                        language = KotlinLanguage.NAME
                        implementation = classDescriptor.fqNameSafe.asString()

                        goal = annotation.argumentValue("name")?.toString()!!
                        description = (classDescriptor.source.getPsi() as? KtClass)?.docComment?.text?.let { processDoc(it) }

                        classDescriptor.unsubstitutedMemberScope
                                .getDescriptorsFiltered(DescriptorKindFilter.VARIABLES)
                                .filterIsInstance<PropertyDescriptor>()
                                .forEach { param ->
                                    addParameter(Parameter().apply {
                                        name = param.name.asString()
                                        description = (param.source.getPsi() as? KtProperty)?.docComment?.let { processDoc(it.text) }
                                    })
                                }
                    }
                }.toList()
    }

    private fun processDoc(text: String): String {
        return text.trim().removePrefix("/*").removeSuffix("*/").lines().map { it.trimStart().removePrefix("*").trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun setIdeaIoUseFallback() {
        if (SystemInfo.isWindows) {
            val properties = System.getProperties()

            properties.setProperty("idea.io.use.nio2", java.lang.Boolean.TRUE.toString())

            if (!(SystemInfo.isJavaVersionAtLeast("1.7") && "1.7.0-ea" != SystemInfo.JAVA_VERSION)) {
                properties.setProperty("idea.io.use.fallback", java.lang.Boolean.TRUE.toString())
            }
        }
    }
}