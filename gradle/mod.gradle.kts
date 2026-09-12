// Shared by both Loom variants. Written against the untyped API because an applied script has no
// type-safe accessors for extensions the applying script declared.
fun prop(key: String) = project.property(key) as String

val java = project.extensions.getByType(JavaPluginExtension::class.java)
java.toolchain.languageVersion.set(JavaLanguageVersion.of(prop("deps.java").toInt()))

project.tasks.named<ProcessResources>("processResources") {
    val tokens = mapOf(
        "version" to project.version.toString(),
        "mod_id" to prop("mod.id"),
        "mod_name" to prop("mod.name"),
        "mod_description" to prop("mod.description"),
        "mc_range" to prop("deps.mc_range"),
        "fabric_loader" to prop("deps.fabric_loader"),
        "java" to prop("deps.java"),
    )
    inputs.properties(tokens)
    filesMatching("fabric.mod.json") { expand(tokens) }
}

project.tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(prop("deps.java").toInt())
}

// Lint only, and no auto-formatter anywhere: Stonecutter rewrites src/main/java in place for the
// active version, so anything that rewrote source would depend on which version that happened to be.
project.plugins.apply("checkstyle")
project.extensions.getByType(CheckstyleExtension::class.java).apply {
    toolVersion = "10.21.4"
    configDirectory.set(rootProject.layout.projectDirectory.dir("config/checkstyle"))
    isIgnoreFailures = false
    maxWarnings = 0
}

/**
 * Only the checked-out form is linted.
 *
 * Every version shares one source tree and Stonecutter rewrites it per version, so the same file
 * reads differently in each. Linting all of them reports an import the 26.x branch needs as unused
 * in the 1.20.1 form, and back again. The form on disk is the one a person reads and the one git
 * stores; the rest are generated, and generated text does not need a style opinion.
 */
val stonecutter = project.extensions.getByName("stonecutter")
val active = stonecutter.javaClass.getMethod("getActive").invoke(stonecutter)
val activeName = active.javaClass.getMethod("getProject").invoke(active) as String

project.tasks.named("checkstyleMain") {
    onlyIf { project.name == activeName }
}

/**
 * A syntax gate for the tools, which no compiler otherwise covers.
 *
 * These scripts are only run by hand and could otherwise sit broken for weeks. This is a parse
 * check and not a style opinion: neither ruff nor ESLint is a dependency of this repository.
 */
val checkTools = project.tasks.register("checkTools") {
    group = "verification"
    description = "Parse the Node and Python tools under tools/."

    val tools = rootProject.layout.projectDirectory.dir("tools").asFile
    inputs.dir(tools).withPropertyName("tools")
    outputs.upToDateWhen { true }
    onlyIf { project.name == activeName }

    doLast {
        val parsers = mapOf(
            "mjs" to listOf("node", "--check"),
            "py" to listOf("python3", "-m", "py_compile"),
        )
        val broken = mutableListOf<String>()
        var checked = 0

        tools.walkTopDown().filter { it.isFile }.forEach { script ->
            val parser = parsers[script.extension] ?: return@forEach
            checked++

            val process = ProcessBuilder(parser + script.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) broken += "${script.name}: ${output.trim()}"
        }

        if (broken.isNotEmpty()) throw GradleException(broken.joinToString("\n"))
        logger.lifecycle("Parsed $checked tool scripts.")
    }
}

project.tasks.named("check") {
    dependsOn(checkTools)
}

project.tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) { rename { "${it}_${prop("mod.id")}" } }
    from(rootProject.file("NOTICE")) { rename { "${it}_${prop("mod.id")}" } }
}
