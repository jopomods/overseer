// web: the Vite/React dashboard. Gradle only wraps npm so that
// `./gradlew build` produces a jar with the UI embedded (ADR-0009).
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val npm = if (isWindows) "npm.cmd" else "npm"

val npmInstall by tasks.registering(Exec::class) {
    group = "web"
    description = "Installs web dependencies (npm ci)."
    workingDir = projectDir
    commandLine(npm, "ci", "--no-audit", "--no-fund")
    inputs.files("package.json", "package-lock.json")
    outputs.file("node_modules/.package-lock.json")
    // Locally, keep an existing node_modules: `npm ci` wipes it, which fails on Windows
    // while a dev server or editor holds files open. CI always does a clean install.
    val inCi = providers.environmentVariable("CI").isPresent
    val modulesDir = file("node_modules")
    onlyIf { inCi || !modulesDir.exists() }
}

val npmBuild by tasks.registering(Exec::class) {
    group = "web"
    description = "Builds the dashboard into web/dist."
    dependsOn(npmInstall)
    workingDir = projectDir
    commandLine(npm, "run", "build")
    inputs.dir("src")
    inputs.dir("public")
    inputs.files("index.html", "package.json", "package-lock.json", "vite.config.ts", "tsconfig.json", "tsconfig.app.json")
    outputs.dir("dist")
}
