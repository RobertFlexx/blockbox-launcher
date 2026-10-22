import org.gradle.api.tasks.bundling.Jar

plugins {
    kotlin("jvm") version "2.0.21"
    application
}
group = "blockbox"
version = "1.2.0"
kotlin { jvmToolchain(21) }
application {
    mainClass.set("blockbox.launcher.MainKt")
    applicationDefaultJvmArgs = listOf("-Xms32m", "-Xmx256m")
}
dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }

val standaloneJar by tasks.registering(Jar::class) {
    group = "distribution"
    archiveFileName.set("blockbox-launcher.jar")
    destinationDirectory.set(layout.buildDirectory.dir("standalone"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
}

val packagingJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
val gameRoot = providers.gradleProperty("gameRoot").orElse(providers.environmentVariable("BLOCKBOX_GAME_ROOT"))
    .orElse(layout.projectDirectory.dir("../blockbox").asFile.absolutePath)
val bundledGame = layout.buildDirectory.dir("bundled-game")
val bundleGame by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Build Blockbox and collect its runtime assets; requires Scala CLI only on the build machine."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("blockbox.launcher.DistributionKt")
    javaLauncher.set(packagingJava)
    inputs.files(provider { fileTree(file(gameRoot.get())) {
        include("src/main/**", "project.scala", "assets/**", "shaderpacks/**", "LICENSE")
        exclude("**/.scala-build/**", "**/.bsp/**")
    } })
    inputs.property("gameRevision", providers.gradleProperty("gameRevision").orElse("local"))
    inputs.property("platform", System.getProperty("os.name") + "/" + System.getProperty("os.arch"))
    outputs.dir(bundledGame)
    doFirst {
        args = listOf("--game-root", file(gameRoot.get()).absolutePath,
            "--output", bundledGame.get().asFile.absolutePath,
            "--java-home", packagingJava.get().metadata.installationPath.asFile.absolutePath,
            "--cache", layout.buildDirectory.dir("game-cache").get().asFile.absolutePath,
            "--revision", providers.gradleProperty("gameRevision").getOrElse("local"))
    }
}
val packageInput by tasks.registering(Sync::class) {
    dependsOn(standaloneJar, bundleGame)
    from(standaloneJar)
    from(bundledGame) { into("game") }
    from("LICENSE") { into("licenses/launcher") }
    into(layout.buildDirectory.dir("package-input"))
}
tasks.register<Exec>("packageApp") {
    group = "distribution"
    description = "Create an application with a private runtime on the current OS."
    dependsOn(packageInput)
    doFirst {
        val os = System.getProperty("os.name").lowercase()
        require(!os.contains("bsd")) { "Use installDist or standaloneJar on BSD; jpackage is not available there." }
        val output = layout.buildDirectory.dir("native").get().asFile
        delete(output.resolve(if (os.contains("mac")) "Blockbox Launcher.app" else "Blockbox Launcher"))
        commandLine(
            packagingJava.get().metadata.installationPath.file("bin/jpackage" + if (os.startsWith("windows")) ".exe" else "").asFile,
            "--type", "app-image", "--name", "Blockbox Launcher", "--app-version", project.version.toString(),
            "--vendor", "Blockbox", "--description", "Manage and play Blockbox instances",
            "--input", layout.buildDirectory.dir("package-input").get().asFile.absolutePath,
            "--main-jar", standaloneJar.get().archiveFileName.get(), "--main-class", application.mainClass.get(),
            "--add-modules", "java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs",
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
            "--java-options", "-Xms32m", "--java-options", "-Xmx256m", "--dest", output.absolutePath
        )
    }
}
tasks.register<Exec>("packageInstaller") {
    group = "distribution"
    description = "Build a normal Windows EXE, macOS DMG, or Linux DEB/RPM installer with the game and runtime included."
    dependsOn("packageApp")
    doFirst {
        val os = System.getProperty("os.name").lowercase()
        val windows = os.startsWith("windows")
        val mac = os.contains("mac")
        val type = providers.gradleProperty("installerType").getOrElse(if (windows) "exe" else if (mac) "dmg" else "deb")
        require(type in if (windows) listOf("exe", "msi") else if (mac) listOf("dmg", "pkg") else listOf("deb", "rpm")) { "Installer type $type cannot be built on $os." }
        val args = mutableListOf(
            packagingJava.get().metadata.installationPath.file("bin/jpackage" + if (windows) ".exe" else "").asFile.absolutePath,
            "--type", type, "--name", "Blockbox Launcher", "--app-version", project.version.toString(),
            "--vendor", "Blockbox", "--description", "Blockbox game and launcher",
            "--license-file", layout.projectDirectory.file("LICENSE").asFile.absolutePath,
            "--app-image", layout.buildDirectory.dir("native/Blockbox Launcher" + if (mac) ".app" else "").get().asFile.absolutePath,
            "--dest", layout.buildDirectory.dir("installers").get().asFile.absolutePath
        )
        when {
            windows -> args += listOf("--win-per-user-install", "--win-dir-chooser", "--win-menu", "--win-menu-group", "Blockbox", "--win-shortcut", "--win-upgrade-uuid", "45add851-b090-5b5d-a877-245dfd64b511")
            mac -> args += listOf("--mac-package-identifier", "io.github.robertflexx.blockbox", "--mac-package-name", "Blockbox")
            else -> args += listOf("--linux-package-name", "blockbox-launcher", "--linux-shortcut", "--linux-app-category", "Game", "--linux-menu-group", "Game")
        }
        commandLine(args)
    }
}
tasks.assemble { dependsOn(standaloneJar) }
