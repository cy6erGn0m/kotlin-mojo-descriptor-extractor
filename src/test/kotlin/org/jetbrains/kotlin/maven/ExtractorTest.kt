package org.jetbrains.kotlin.maven

import org.apache.maven.artifact.*
import org.apache.maven.artifact.handler.*
import org.apache.maven.plugin.descriptor.*
import org.apache.maven.project.*
import org.apache.maven.tools.plugin.*
import org.junit.*
import org.mockito.*
import java.io.*
import java.net.*
import java.nio.file.*
import java.util.jar.*
import kotlin.test.*

/**
 * Author: Sergey Mashkov
 */
class ExtractorTest {
    @Test
    fun smokeTest() {
        val project = Mockito.mock(MavenProject::class.java)
        Mockito.`when`(project.compileSourceRoots).thenReturn(mutableListOf("src/test/resources/root"))
        Mockito.`when`(project.dependencyArtifacts).thenReturn(
                classPaths(ExtractorTest::class.java.classLoader).map { cp ->
                    DefaultArtifact("cp", "cp-${cp.fileName}", "1.0", "compile", "jar", null, DefaultArtifactHandler()).apply {
                        file = cp.toFile()
                    }
                }.toSet())

        val descriptors = KotlinMojoDescriptorExtractor().execute(DefaultPluginToolsRequest(project, PluginDescriptor()))

        assertEquals(1, descriptors.size)
        val mojo = descriptors[0]

        assertEquals("myMojo", mojo.goal)
        assertEquals("My mojo", mojo.description)

        assertEquals(listOf("classpath"), mojo.parameters.map { it.name })
        assertEquals(listOf("My param"), mojo.parameters.map { it.description })
    }

    private fun classPaths(classLoader: ClassLoader) = (
            classLoader.classPath()
                    + ClassLoader.getSystemClassLoader().classPath()
                    + (Thread.currentThread().contextClassLoader?.classPath() ?: emptyList())
                    + propertyClassPath("java.class.path")
                    + propertyClassPath("sun.boot.class.path")
            ).distinct().filter { Files.exists(it) }

    private fun ClassLoader.classPath() = (classPathImpl() + manifestClassPath()).distinct()

    private fun ClassLoader.classPathImpl(): List<Path> {
        val parentUrls = parent?.classPathImpl() ?: emptyList()

        return when {
            this is URLClassLoader -> urLs.filterNotNull().map(URL::toURI).mapNotNull { ifFailed(null) { Paths.get(it) } } + parentUrls
            else -> parentUrls
        }
    }

    private fun ClassLoader.manifestClassPath() =
            getResources("META-INF/MANIFEST.MF")
                    .asSequence()
                    .mapNotNull { ifFailed(null) { it.openStream().use { Manifest().apply { read(it) } } } }
                    .flatMap { it.mainAttributes?.getValue("Class-Path")?.splitToSequence(" ")?.filter(String::isNotBlank) ?: emptySequence() }
                    .mapNotNull { ifFailed(null) { Paths.get(URI.create(it)) } }
                    .toList()

    private fun propertyClassPath(key: String) = System.getProperty(key)
            ?.split(File.pathSeparator)
            ?.filter { it.isNotEmpty() }
            ?.map { Paths.get(it) }
            ?: emptyList()

    private inline fun <R> ifFailed(default: R, block: () -> R) = try {
        block()
    } catch (t: Throwable) {
        default
    }
}