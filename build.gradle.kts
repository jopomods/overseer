// Root build: shared coordinates only. Each subproject configures itself.
// See docs/ARCHITECTURE.md for the module layout.

allprojects {
    group = providers.gradleProperty("maven_group").get()
    // Single source of truth for the version, managed by release-please (ADR-0013).
    version = rootProject.file("version.txt").readText().trim()
}

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(17)
        }
    }
}
