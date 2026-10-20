import org.gradle.api.tasks.bundling.Jar

plugins {
    kotlin("jvm") version "2.0.21"
    application
}
group = "blockbox"
version = "1.1.0"
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
tasks.register<Exec>("packageApp") {
    group = "distribution"
    description = "Create an application with a private runtime on the current OS."
    dependsOn(standaloneJar)
    doFirst {
        val os = System.getProperty("os.name").lowercase()
        require(!os.contains("bsd")) { "Use installDist or standaloneJar on BSD; jpackage is not available there." }
        val output = layout.buildDirectory.dir("native").get().asFile
        delete(output.resolve(if (os.contains("mac")) "Blockbox Launcher.app" else "Blockbox Launcher"))
        commandLine(
            packagingJava.get().metadata.installationPath.file("bin/jpackage" + if (os.startsWith("windows")) ".exe" else "").asFile,
            "--type", "app-image", "--name", "Blockbox Launcher", "--app-version", project.version.toString(),
            "--vendor", "Blockbox", "--description", "Manage and play Blockbox instances",
            "--input", standaloneJar.get().destinationDirectory.get().asFile.absolutePath,
            "--main-jar", standaloneJar.get().archiveFileName.get(), "--main-class", application.mainClass.get(),
            "--add-modules", "java.desktop,java.logging,java.management,jdk.crypto.ec",
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
            "--java-options", "-Xms32m", "--java-options", "-Xmx256m", "--dest", output.absolutePath
        )
    }
}
tasks.assemble { dependsOn(standaloneJar) }
