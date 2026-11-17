// core: Minecraft-free heart of Overseer (ADR-0002).
// Must never depend on Minecraft, Forge or any mod API.
plugins {
    `java-library`
}

dependencies {
    // Provided at runtime by Minecraft; compile only against the same version (ADR-0003).
    compileOnly(libs.netty.codec.http)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
