// core: Minecraft-free heart of Overseer (ADR-0002).
// Must never depend on Minecraft, Forge or any mod API.
plugins {
    `java-library`
}

dependencies {
    // Provided at runtime by Minecraft; compile only against the same versions (ADR-0003).
    compileOnly(libs.netty.codec.http)
    compileOnly(libs.slf4j.api)
    compileOnly(libs.gson)

    // Bundled into the mod jar by the platform module (ADR-0006).
    implementation(libs.sqlite.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.gson)
    testImplementation(libs.netty.codec.http)
    testRuntimeOnly(libs.slf4j.simple)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// --- Developer tools: recording export and the simulator (not part of the mod) ---------
// They run outside Minecraft, so the libraries Minecraft normally provides are added here.
val tools: Configuration by configurations.creating {
    extendsFrom(configurations.runtimeClasspath.get())
}
dependencies {
    tools(libs.gson)
    tools(libs.netty.codec.http)
    tools(libs.slf4j.simple)
}

// ./gradlew :core:exportRecording --args="<overseer.db> <out.jsonl> [intervalMillis] [--raw]"
tasks.register<JavaExec>("exportRecording") {
    group = "overseer"
    description = "Exports an Overseer database into an (anonymized) recording."
    classpath = sourceSets.main.get().runtimeClasspath + tools
    mainClass.set("io.github.jopojp.overseer.core.recording.ExportRecordingMain")
    workingDir = rootDir
}

// ./gradlew :core:runSimulator [--args="<dir or .jsonl> ..."]
tasks.register<JavaExec>("runSimulator") {
    group = "overseer"
    description = "Runs Overseer without Minecraft, replaying recordings (default: fixtures/recordings)."
    // Serve the built UI too when it exists (otherwise use `npm run dev`).
    val webDist = rootProject.layout.projectDirectory.dir("web/dist")
    val simWeb = layout.buildDirectory.dir("simulator-web")
    doFirst {
        project.delete(simWeb)
        if (webDist.asFile.exists()) {
            project.copy {
                from(webDist)
                into(simWeb.get().dir("overseer-web"))
            }
        }
    }
    classpath = sourceSets.main.get().runtimeClasspath + tools + files(simWeb)
    mainClass.set("io.github.jopojp.overseer.core.sim.Simulator")
    workingDir = projectDir
    args = listOf("../fixtures/recordings")
}
