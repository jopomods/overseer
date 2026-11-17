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

repositories {
    // Mods without their own Maven (Flux Networks) come from CurseForge via CurseMaven.
    maven("https://cursemaven.com") {
        content { includeGroup("curse.maven") }
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
    }

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
            sourceSet(project(":core").sourceSets.main.get())
        }
    }
}

dependencies {
    implementation(project(":core"))

    // Optional integrations: compile against them, never require them (AGENTS.md rule 3).
    // They are present in dev runs so integrations can be exercised.
    "modCompileOnly"(libs.flux.networks)
    "modRuntimeOnly"(libs.flux.networks)

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
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
