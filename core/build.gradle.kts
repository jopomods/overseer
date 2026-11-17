// core: Minecraft-free heart of Overseer (ADR-0002).
// Must never depend on Minecraft, Forge or any mod API.
plugins {
    `java-library`
}

dependencies {
    // Provided at runtime by Minecraft; compile only against the same versions (ADR-0003).
    compileOnly(libs.netty.codec.http)
    compileOnly(libs.slf4j.api)

    // Bundled into the mod jar by the platform module (ADR-0006).
    implementation(libs.sqlite.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
