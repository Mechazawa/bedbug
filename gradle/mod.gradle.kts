import java.util.concurrent.ConcurrentHashMap

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
 * Runs Bedrock item and block ids through the mod's own mapping, for `tools/census/ids.py`.
 *
 * The mapping asks the registry which spellings a version has, so it runs on the unit tests'
 * classpath, which can bootstrap one without a game. `-PidsIn` and `-PidsOut` name the two files.
 */
project.tasks.register<JavaExec>("idCensus") {
    group = "verification"
    description = "Answer the id census's Bedrock ids with this mod's Java ones."
    onlyIf { project.name == activeName }
    dependsOn("testClasses")

    val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("at.ioexception.mcaddon.convert.IdCensus")
    args(project.findProperty("idsIn")?.toString() ?: "", project.findProperty("idsOut")?.toString() ?: "")
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
 * Puts the synthetic add-on where the game tests will find it.
 *
 * Without a pack the tests can only assert what holds with no add-on loaded, which leaves the whole
 * of the conversion untested in a running world. The pack is this repository's own, built from
 * `tools/testpack`, so nothing anybody else authored is copied anywhere.
 */
fun installTestPackInto(name: String, runDirectory: String) = project.tasks.register(name) {
    group = "verification"
    description = "Build the synthetic add-on into $runDirectory."

    val generator = rootProject.layout.projectDirectory.file("tools/testpack/build.py").asFile
    val addons = project.layout.buildDirectory.dir("$runDirectory/addons")
    inputs.dir(rootProject.layout.projectDirectory.dir("tools/testpack"))
    outputs.dir(addons)

    // `-Paddons=<directory>` adds every `.mcaddon` in it beside the synthetic one, so the same tests
    // can be run against a real pack that may not be committed here.
    val extra = (project.findProperty("addons") as String?)?.let { File(it) }
    if (extra != null) inputs.dir(extra)

    doLast {
        val target = addons.get().asFile
        target.mkdirs()
        target.listFiles { file -> file.name != "test-addon.mcaddon" }?.forEach { it.delete() }
        extra?.listFiles { file -> file.name.endsWith(".mcaddon") }?.forEach { it.copyTo(File(target, it.name)) }

        val archive = File(target, "test-addon.mcaddon")
        val process = ProcessBuilder("python3", generator.absolutePath, "--out", archive.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("The test pack would not build: $output")
    }
}

val installTestPack = installTestPackInto("installTestPack", "run/gameTest")
val installClientTestPack = installTestPackInto("installClientTestPack", "run/clientGameTest")

// Each run places its tests somewhere new, so a world kept between runs only ever grows.
project.tasks.named("runGameTest") {
    dependsOn(installTestPack)
    val world = project.layout.buildDirectory.dir("run/gameTest/world")
    doFirst { project.delete(world) }
}

/**
 * The client game tests, run for every version that ships the API they are written against.
 *
 * Each one opens a window and drives a real client through a world, which is what reaches a screen,
 * a model and a key press. Only the 26.x targets have the API, and both of them run the tests, because
 * a spelling that differs between the two is exactly what a test on one version alone would miss.
 */
/**
 * The add-on the client game tests' server hands out without having loaded it, built from
 * `tools/testpack/served`. The server and the client share one process and one `addons/`, so this is
 * how a client joining it can lack an add-on the server has.
 */
val installServedPack = project.tasks.register("installServedPack") {
    group = "verification"
    description = "Build the add-on the client game tests' server hands out into run/clientGameTest/addons-served."

    val generator = rootProject.layout.projectDirectory.file("tools/testpack/build_served.py").asFile
    val served = project.layout.buildDirectory.dir("run/clientGameTest/addons-served")
    inputs.dir(rootProject.layout.projectDirectory.dir("tools/testpack"))
    outputs.dir(served)

    doLast {
        val target = served.get().asFile
        target.mkdirs()
        val archive = File(target, "served-addon.mcaddon")
        val process = ProcessBuilder("python3", generator.absolutePath, "--out", archive.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("The served pack would not build: $output")
    }
}

project.tasks.matching { it.name == "runClientGameTest" }.configureEach {
    dependsOn(installClientTestPack, installServedPack)
    (this as JavaExec).systemProperty("mcaddon.served",
        project.layout.buildDirectory.dir("run/clientGameTest/addons-served").get().asFile.absolutePath)
    val saves = project.layout.buildDirectory.dir("run/clientGameTest/saves")
    doFirst { project.delete(saves) }

    // The multiplayer tests start a dedicated server inside the client's own process. C2ME ends the
    // whole process once a dedicated server has shut down, unless its configuration says otherwise,
    // which would end the run after the first of them.
    val c2meConfig = project.layout.buildDirectory.file("run/clientGameTest/config/c2me.toml")
    doFirst {
        val file = c2meConfig.get().asFile
        file.parentFile.mkdirs()
        file.writeText("version = 3\n\n[fixes]\n\tdisableLoggingShutdownHook = false\n")
    }
}
/**
 * Every checkout on a machine draws its client game tests in windows of their own, and past about
 * four at once the desktop starves them until the server's watchdog ends a run. With `cpu-lock`
 * installed, a run first waits for one of four slots of its `minecraft-client` pool and holds it
 * through a child process that ends once the task does or the Gradle daemon goes away.
 * `-PclientSlots=<n>` changes the count, and without `cpu-lock` nothing waits.
 */
val clientSlotHolders = ConcurrentHashMap<String, Process>()
val releaseClientSlot = project.tasks.register("releaseClientSlot") {
    doLast { clientSlotHolders.remove(project.path)?.destroy() }
}
project.tasks.matching { it.name == "runClientGameTest" }.configureEach {
    finalizedBy(releaseClientSlot)
    doFirst {
        val lock = File(System.getProperty("user.home"), ".claude/bin/cpu-lock")
        if (!lock.canExecute()) return@doFirst
        val slots = project.findProperty("clientSlots")?.toString() ?: "4"
        val holder = ProcessBuilder(lock.path, "--pool", "minecraft-client", "--slots", slots, "--",
            "sh", "-c", "echo held; read _")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        logger.lifecycle("Waiting for a minecraft-client slot (cpu-lock --status shows the queue)")
        holder.inputStream.bufferedReader().readLine()
        clientSlotHolders[project.path] = holder
    }
}
project.tasks.named("check") {
    dependsOn(project.tasks.matching { it.name == "runClientGameTest" })
}
project.tasks.matching { it.name == "checkstyleGametest" }.configureEach {
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

/**
 * Checks the script modules link, which parsing them one at a time does not.
 *
 * A module is linked before any of it runs, so one name declared twice, or an import of a name
 * nothing exports, is a failure at load time that switches off every pack's script at once. Both
 * have happened here, and `node --check` sees neither.
 */
val checkScriptModules = project.tasks.register("checkScriptModules") {
    group = "verification"
    description = "Link every module a pack can import."

    val checker = rootProject.layout.projectDirectory.file("tools/modules/check.py").asFile
    val modules = rootProject.layout.projectDirectory
        .dir("src/main/resources/assets/mcaddon/script").asFile
    inputs.dir(modules).withPropertyName("modules")
    outputs.upToDateWhen { true }
    onlyIf { project.name == activeName }

    doLast {
        val process = ProcessBuilder("python3", checker.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException(output.trim())
        logger.lifecycle(output.trim())
    }
}

project.tasks.named("check") {
    dependsOn(checkTools, checkScriptModules)
}

project.tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) { rename { "${it}_${prop("mod.id")}" } }
    from(rootProject.file("NOTICE")) { rename { "${it}_${prop("mod.id")}" } }
}

/**
 * Lets a run open a world by itself, which is how the client half reaches a player at all.
 *
 * A client stopped at the title screen has nobody playing, so everything a pack does with a player
 * goes untouched: the whole `Player` surface, the forms a pack opens, and every event carrying
 * somebody who used something. `-PquickPlay=<world>` hands the game a save to load on startup, and
 * `-PjoinServer=<host>:<port>` sends it to a server instead, which is the half a saved world cannot
 * reach: the registries the server sends and what a pack's content costs to send them.
 */
val quickPlayWorld = project.findProperty("quickPlay") as String?
val joinServer = project.findProperty("joinServer") as String?
val windowSize = project.findProperty("windowSize") as String?

/** What the game is told on startup, beyond what Loom already passes it. */
val clientArgs = buildList {
    if (!quickPlayWorld.isNullOrBlank()) addAll(listOf("--quickPlaySingleplayer", quickPlayWorld))
    if (!joinServer.isNullOrBlank()) addAll(listOf("--quickPlayMultiplayer", joinServer))
    // A window the size of a postage stamp makes a captured scene unreadable.
    if (!windowSize.isNullOrBlank()) {
        val across = windowSize.split("x")
        if (across.size == 2) addAll(listOf("--width", across[0], "--height", across[1]))
    }
}

/**
 * `runJoinClient` is a second client with a run directory and an add-on set of its own, which is
 * what a player joining somebody else's game is. `verify.py join` and `verify.py lan` start it
 * beside the dedicated server or the first client, and the property tells the two clients apart in
 * the process table.
 */
run {
    val loom = project.extensions.getByName("loom")
    val runs = loom.javaClass.getMethod("getRuns").invoke(loom)
    val create = runs.javaClass.getMethod("create", String::class.java)
    val getByName = runs.javaClass.getMethod("getByName", String::class.java)
    val guest = create.invoke(runs, "joinClient")
    guest.javaClass.getMethod("client").invoke(guest)
    guest.javaClass.getMethod("runDir", String::class.java).invoke(guest, "run/join-client")
    guest.javaClass.getMethod("vmArg", String::class.java).invoke(guest, "-Dmcaddon.role=guest")
    // The second client waits this many seconds before activating a server's add-ons.
    val slowJoin = project.findProperty("slowJoin") as String?
    if (!slowJoin.isNullOrBlank()) {
        guest.javaClass.getMethod("vmArg", String::class.java).invoke(guest, "-Dmcaddon.slowJoin=$slowJoin")
    }
    // A dedicated server hands out every archive in this directory as well as its own add-ons.
    val servedDirectory = project.findProperty("served") as String?
    if (!servedDirectory.isNullOrBlank()) {
        val server = getByName.invoke(runs, "server")
        server.javaClass.getMethod("vmArg", String::class.java)
            .invoke(server, "-Dmcaddon.served=$servedDirectory")
    }
    if (clientArgs.isNotEmpty()) {
        for (name in listOf("client", "joinClient")) {
            val config = getByName.invoke(runs, name)
            config.javaClass.getMethod("programArgs", Array<String>::class.java)
                .invoke(config, clientArgs.toTypedArray())
        }
    }
}
