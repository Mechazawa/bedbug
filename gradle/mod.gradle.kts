// Shared by both Loom variants. Written against the untyped API because an applied script has no
// type-safe accessors for extensions the applying script declared.
fun prop(key: String) = project.property(key) as String

val java = project.extensions.getByType(JavaPluginExtension::class.java)
java.toolchain.languageVersion.set(JavaLanguageVersion.of(prop("deps.java").toInt()))

/**
 * GraalJS runs the behaviour packs' scripts, and it ships nested in the jar because a player cannot
 * be asked to install a JavaScript engine.
 *
 * The artifacts are listed rather than resolved through the `js-community` aggregator POM, because
 * Loom nests declared dependencies and not transitive ones. Same version for all of them.
 */
val graalArtifacts = listOf(
    "org.graalvm.polyglot:polyglot",
    "org.graalvm.js:js-language",
    "org.graalvm.regex:regex",
    "org.graalvm.sdk:collections",
    "org.graalvm.sdk:jniutils",
    "org.graalvm.sdk:nativeimage",
    "org.graalvm.sdk:word",
    "org.graalvm.shadowed:icu4j",
    "org.graalvm.shadowed:xz",
    "org.graalvm.truffle:truffle-api",
    "org.graalvm.truffle:truffle-compiler",
    "org.graalvm.truffle:truffle-runtime",
)

graalArtifacts.forEach { artifact ->
    project.dependencies.add("implementation", "$artifact:${prop("deps.graaljs")}")
    project.dependencies.add("include", "$artifact:${prop("deps.graaljs")}")
}

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
 * Unit tests, run for the checked-out version only, for the same reason linting is.
 *
 * What they cover is everything that is a function of its input rather than of a running game: the
 * Molang evaluator, the readers for Targa and Ogg, pack discovery and ordering, the command
 * translation, and the channel arithmetic of a texture set. Anything needing a world belongs in the
 * game tests instead.
 */
project.dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter:5.11.4")
project.dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")

project.tasks.named<Test>("test") {
    useJUnitPlatform()
    onlyIf { project.name == activeName }
    testLogging { events("failed") }
}

project.tasks.named<Checkstyle>("checkstyleTest") {
    onlyIf { project.name == activeName }
}

// Compiling them for the other versions too would report the same source as broken under whichever
// form Stonecutter did not write, which is what gating every other step on the active version avoids.
project.tasks.named<JavaCompile>("compileTestJava") {
    onlyIf { project.name == activeName }
}

/**
 * The game tests, run for every version.
 *
 * `fabricApi.configureTests` hangs these off `check`, so a failing one fails the build. They start a
 * headless server and run against a real world, which is the half a unit test cannot reach: the
 * commands, the entity events and the tick that has to survive a script throwing.
 *
 * Unlike the linting and the unit tests, these are not gated on the checked-out version. They live in
 * `src/main/java`, which Stonecutter rewrites per version, so each one runs against the game it was
 * written for. A call that exists on only some versions is exactly what they are here to catch.
 */

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
