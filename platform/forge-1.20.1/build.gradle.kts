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
