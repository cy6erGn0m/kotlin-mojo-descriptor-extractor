package root

import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter

/**
 * My mojo
 */
@Mojo(name = "myMojo")
class MyMojo {

    /**
     * My param
     */
    @Parameter(defaultValue = "\${project.compileClasspathElements}", required = true, readonly = true)
    var classpath: List<String>? = null
}
