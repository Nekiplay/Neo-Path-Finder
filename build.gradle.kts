plugins {
    kotlin("jvm") version "2.4.0"
    id("net.fabricmc.fabric-loom") version "1.17.11"
    id("io.freefair.lombok") version "8.6"
}

val baseGroup: String = findProperty("baseGroup") as String
val mcVersion: String = findProperty("mcVersion") as String
val modVersion = project.version.toString()
val loomVersion: String = findProperty("loomVersion") as String
val loaderVersion: String = findProperty("loaderVersion") as String
val fabricApiVersion: String = findProperty("fabricApiVersion") as String
val kotlinLoaderVersion: String = findProperty("kotlinLoaderVersion") as String
val mixinGroup = "$baseGroup.mixin"
val modid: String = findProperty("modid") as String
val modName: String = findProperty("modName") as String

group = baseGroup

// Toolchains:
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

kotlin {
    jvmToolchain(25)
}

// Minecraft configuration:
loom {
    accessWidenerPath.set(file("src/main/resources/neopathfinder.accesswidener"))
}

// Dependencies:
repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://repo.spongepowered.org/maven/")
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$kotlinLoaderVersion")

    compileOnly("org.jetbrains:annotations:26.0.1")
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    implementation("com.google.guava:guava:33.2.1-jre")
}

// Tasks:

val lintDeprecation: Boolean = (findProperty("lintDeprecation") as String?)?.toBoolean() ?: false

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if (lintDeprecation) {
        options.compilerArgs.add("-Xlint:deprecation")
    }
}

tasks.withType<Jar> {
    archiveBaseName.set(modName)
}

tasks.processResources {
    inputs.property("version", modVersion)
    inputs.property("mcversion", mcVersion)
    inputs.property("loaderVersion", loaderVersion)
    inputs.property("modid", modid)
    inputs.property("modName", modName)
    inputs.property("mixinGroup", mixinGroup)

    filesMatching(listOf("fabric.mod.json", "mixins.$modid.json", "version.properties")) {
        expand(inputs.properties)
    }

    rename("(.+_at.cfg)", "META-INF/$1")
}

tasks.assemble.get().dependsOn(tasks.jar)

tasks.withType<AbstractArchiveTask> {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}