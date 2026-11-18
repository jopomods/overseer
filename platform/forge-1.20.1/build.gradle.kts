// platform/forge-1.20.1: everything that touches Minecraft, Forge or other mods.
// Built with ModDevGradle Legacy (ADR-0002).
plugins {
    java
    alias(libs.plugins.moddevgradle.legacy)
}

val modId = providers.gradleProperty("mod_id").get()
val mcVersion = libs.versions.minecraft.get()

base {
    // Final artifact: overseer-X.Y.Z+forge-1.20.1.jar (ADR-0013)
    archivesName.set(modId)
}
version = "${project.version}+forge-$mcVersion"

// GameTest suites (ADR-0015): which optional mods a dev or GameTest server loads, and which
// GameTest classes run. Each suite gets its own server and world, so tests of one never disturb
// another. Everything is always compiled against all mods; a suite only changes the runtime.
//   -PgametestSuite=all        everything (default; also runServer/runClient)
//   base, profiler             no optional mods (AGENTS.md rule 3: Overseer runs without them)
//   flux, ae2, teams, ae2-teams the mod(s) of the integration under test
// -PbareMods=true is kept as an alias for the old "no optional mods" run (base + profiler).
data class Suite(val mods: Set<String>)
val suites = mapOf(
    "all" to Suite(setOf("flux", "ae2", "teams")),
    "bare" to Suite(emptySet()),
    "base" to Suite(emptySet()),
    "profiler" to Suite(emptySet()),
    "flux" to Suite(setOf("flux")),
    "ae2" to Suite(setOf("ae2")),
    "teams" to Suite(setOf("teams")),
    "ae2-teams" to Suite(setOf("ae2", "teams")),
)
val bareMods = providers.gradleProperty("bareMods").map { it.toBoolean() }.getOrElse(false)
val suiteName = providers.gradleProperty("gametestSuite").getOrElse(if (bareMods) "bare" else "all")
val suite = suites[suiteName] ?: error("Unknown gametestSuite '$suiteName'; one of ${suites.keys}")

// In-game tests (Forge GameTest) live in their own source set and never ship in the jar.
val gametest: SourceSet = sourceSets.create("gametest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[gametest.compileClasspathConfigurationName].extendsFrom(configurations.compileClasspath.get())
configurations[gametest.runtimeClasspathConfigurationName].extendsFrom(configurations.runtimeClasspath.get())

repositories {
    // Mods without their own Maven (Flux Networks) come from CurseForge via CurseMaven.
    maven("https://cursemaven.com") {
        content { includeGroup("curse.maven") }
    }
    maven("https://modmaven.dev") {
        content { includeGroup("appeng") }
    }
    maven("https://maven.ftb.dev/releases") {
        content { includeGroup("dev.ftb.mods") }
    }
    maven("https://maven.architectury.dev") {
        content { includeGroup("dev.architectury") }
    }
}

legacyForge {
    version = libs.versions.forge.get()
    validateAccessTransformers = true

    parchment {
        minecraftVersion = libs.versions.parchment.minecraft.get()
        mappingsVersion = libs.versions.parchment.asProvider().get()
    }

    runs {
        create("server") {
            server()
            programArgument("--nogui")
        }
        create("client") {
            client()
        }
        // ./gradlew :platform:forge-1.20.1:runGameTestServer runs all GameTests and exits
        // non-zero on failure. Used in CI.
        create("gameTestServer") {
            type = "gameTestServer"
            sourceSet = gametest
            gameDirectory = project.file("run/gametest-$suiteName")
            // Test classes carry no @GameTestHolder (Forge would load every holder class, also those
            // of absent mods): GameTestSuites registers exactly the classes of the chosen suite.
            systemProperty("forge.enabledGameTestNamespaces", modId)
            systemProperty("overseer.gametestSuite", suiteName)
        }
    }

    addModdingDependenciesTo(gametest)

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
            sourceSet(project(":core").sourceSets.main.get())
            sourceSet(gametest)
        }
    }
}

// One mixin: completes Forge's block entity timer for the tick profiler (ADR-0014).
mixin {
    add(sourceSets.main.get(), "overseer.refmap.json")
    config("overseer.mixins.json")
}

dependencies {
    implementation(project(":core"))
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")

    // Optional integrations: compile against them, never require them (AGENTS.md rule 3).
    // They are present in dev runs so integrations can be exercised.
    "modCompileOnly"(libs.flux.networks)
    "modCompileOnly"(libs.ae2)
    "modCompileOnly"(libs.ftb.teams)
    if ("teams" in suite.mods) {
        "modRuntimeOnly"(libs.ftb.teams)
        "modRuntimeOnly"(libs.ftb.library)
        "modRuntimeOnly"(libs.architectury)
    }
    if ("flux" in suite.mods) "modRuntimeOnly"(libs.flux.networks)
    if ("ae2" in suite.mods) {
        "modRuntimeOnly"(libs.ae2)
        "modRuntimeOnly"(libs.guideme)
    }

    // Libraries Minecraft does not ship, embedded with Forge Jar-in-Jar (ADR-0003, ADR-0006).
    // FML picks one copy if several mods embed the same library; ranges say what we accept.
    jarJar(libs.sqlite.jdbc) {
        version {
            strictly("[3.45,4)")
            prefer(libs.versions.sqlite.jdbc.get())
        }
    }
    // Must stay on Minecraft's Netty 4.1 line (4.1.82 in 1.20.1).
    jarJar(libs.netty.codec.http) {
        version {
            strictly("[4.1.82.Final,4.2)")
            prefer(libs.versions.netty.get())
        }
    }
    "additionalRuntimeClasspath"(libs.sqlite.jdbc)
    "additionalRuntimeClasspath"(libs.netty.codec.http)
}

// Bundle the core classes into the mod jar. core is plain Java (no Minecraft
// references), so it passes through reobfuscation unchanged.
tasks.jar {
    from(project(":core").sourceSets.main.get().output)
    manifest.attributes(mapOf("MixinConfigs" to "overseer.mixins.json"))
}

// Embed the built web dashboard (web/dist) into the jar under /overseer-web.
// Skip with -PskipWeb=true for fast Java-only iterations.
val skipWeb = providers.gradleProperty("skipWeb").map { it.toBoolean() }.getOrElse(false)
tasks.processResources {
    val props = mapOf(
        "mod_id" to modId,
        "mod_version" to project.version.toString().substringBefore('+'),
        "minecraft_version" to mcVersion,
    )
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") { expand(props) }

    if (!skipWeb) {
        dependsOn(":web:npmBuild")
        from(project(":web").layout.projectDirectory.dir("dist")) {
            into("overseer-web")
        }
    }
}

// Unit tests for pure platform code (mapping, access rules). Game-dependent behavior is
// covered by GameTests on a real server.
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.gson) // Flux action parameters are JSON (provided by Minecraft in game)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// GameTests need no real terrain: a flat world skips most of the spawn-area generation,
// which dominated the GameTest run time in CI.
val gameTestServerProperties by tasks.registering {
    // Into the suite's own game directory (run/gametest-<suite>), where the server reads it.
    val file = project.file("run/gametest-$suiteName/server.properties")
    outputs.file(file)
    doLast {
        file.parentFile.mkdirs()
        // In .properties files the colon must be escaped: minecraft\:flat
        file.writeText("level-type=minecraft\\:flat\ngenerate-structures=false\n")
    }
}
tasks.matching { it.name == "prepareGameTestServerRun" }.configureEach {
    dependsOn(gameTestServerProperties)
}

// ./gradlew gameTestAll runs every GameTest suite one after another, each in its own server and
// world: locally the same as CI runs in parallel (ADR-0015). One suite: -PgametestSuite=<name>.
val suiteOrder = listOf("base", "profiler", "flux", "ae2", "teams", "ae2-teams")
val suiteRuns = suiteOrder.map { name ->
    tasks.register<GradleBuild>("gameTest-$name") {
        group = "verification"
        description = "GameTests of suite '$name' in their own server"
        dir = rootDir
        buildName = "gametest-$name"
        tasks = listOf(":platform:forge-1.20.1:runGameTestServer")
        startParameter.projectProperties = mapOf("gametestSuite" to name, "skipWeb" to "true")
    }
}
suiteRuns.zipWithNext { a, b -> b.configure { mustRunAfter(a) } }
tasks.register("gameTestAll") {
    group = "verification"
    description = "All GameTest suites, one after another"
    dependsOn(suiteRuns)
}
