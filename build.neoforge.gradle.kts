import java.util.concurrent.TimeUnit

plugins {
    id("java")
    id("java-library")
    kotlin("jvm") version "2.4.0"
    id("com.google.devtools.ksp") version "2.3.9"
    id("net.neoforged.moddev") version "2.0.147"
    id("dev.kikugie.fletching-table.neoforge") version "0.1.0-alpha.22"
    id("com.gradleup.shadow") version "9.4.2"
    id("me.modmuss50.mod-publish-plugin") version "2.1.1"
}

val baseVersion = property("mod_version").toString()
val dynamicVersion = if (baseVersion.endsWith("-SNAPSHOT")) {
    val lastReleaseTag = runGit("describe", "--tags", "--match", "[0-9]*.[0-9]*.[0-9]*", "--abbrev=0")
    if (lastReleaseTag != null) {
        val countStr = runGit("rev-list", "$lastReleaseTag..HEAD", "--count")
        val count = countStr?.toIntOrNull() ?: 0
        if (count > 0) "$baseVersion.$count" else baseVersion
    } else baseVersion
} else baseVersion
version = dynamicVersion

base.archivesName = "${property("mod_id")}-neoforge-mc${property("minecraft_version")}"

// Access transformer per version bucket.
val atFile = when {
    stonecutter.eval(stonecutter.current.version, ">=1.21.11") -> "easyauth.1.21.11.cfg"
    stonecutter.eval(stonecutter.current.version, ">=1.21.9") -> "easyauth.1.21.9.cfg"
    stonecutter.eval(stonecutter.current.version, ">=1.21.6") -> "easyauth.1.21.6.cfg"
    stonecutter.eval(stonecutter.current.version, ">=1.21.5") -> "easyauth.1.21.5.cfg"
    stonecutter.eval(stonecutter.current.version, ">=1.20.3") -> "easyauth.1.20.3.cfg"
    stonecutter.eval(stonecutter.current.version, ">=1.20.2") -> "easyauth.1.20.2.cfg"
    else -> throw GradleException("Access transformer is missing for Minecraft ${stonecutter.current.version})")
}

val atSource = rootProject.file("src/main/resources/accesstransformer/$atFile")

repositories {
    mavenCentral()
    maven(url = "https://maven.neoforged.net/releases")
    maven(url = "https://oss.sonatype.org/content/repositories/snapshots")
    maven(url = "https://api.modrinth.com/maven")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

neoForge {
    version = property("neoforge_version").toString()

    accessTransformers.from(atSource)

    runs {
        create("server") {
            server()
            gameDirectory.set(file("run"))
        }
    }

    mods {
        create(property("mod_id").toString()) {
            sourceSet(sourceSets.main.get())
        }
    }
}


// Fletching Table populates easyauth.mixins.json per-version;
// applyMixinConfig is off because neoforge.mods.toml already declares [[mixins]].
fletchingTable {
    neoforge {
        applyMixinConfig = false
    }
    mixins.create("main") {
        mixin("default", "easyauth.mixins.json")
    }
}

// Configuration that gets shaded (relocated) into the main jar.
val shaded: Configuration by configurations.creating
configurations {
    compileOnly { extendsFrom(shaded) }
    runtimeOnly { extendsFrom(shaded) }
}

// Dev classpath + nested JAR (NeoForge JarJar), the analogue of Fabric's `include`.
fun DependencyHandlerScope.implementAndJarJar(notation: String, version: String) {
    implementation(notation)
    jarJar(notation) {
        version { strictly("[$version,)"); prefer(version) }
    }
}

dependencies {
    // LuckPerms is server-side; compile-only, optional at runtime.
    compileOnly("net.luckperms:api:${property("luckperms_version")}")
    implementAndJarJar("io.github.llamalad7:mixinextras-neoforge:0.5.4", "0.5.4")

    implementAndJarJar("de.mkammerer:argon2-jvm:${property("argon2_version")}", property("argon2_version").toString())
    implementAndJarJar("de.mkammerer:argon2-jvm-nolibs:${property("argon2_version")}", property("argon2_version").toString())
    // No jna: NeoForge/Minecraft already ships it (old lines pin it via `strictly`).
    implementAndJarJar("at.favre.lib:bcrypt:${property("bcrypt_version")}", property("bcrypt_version").toString())
    implementAndJarJar("at.favre.lib:bytes:${property("bytes_version")}", property("bytes_version").toString())
    implementAndJarJar("org.iq80.leveldb:leveldb:${property("leveldb_version")}", property("leveldb_version").toString())
    implementAndJarJar("org.iq80.leveldb:leveldb-api:${property("leveldb_version")}", property("leveldb_version").toString())
    implementAndJarJar("com.mysql:mysql-connector-j:${property("mysql_version")}", property("mysql_version").toString())
    implementAndJarJar("org.xerial:sqlite-jdbc:${property("sqlite_version")}", property("sqlite_version").toString())
    implementAndJarJar("org.postgresql:postgresql:${property("postgresql_version")}", property("postgresql_version").toString())

    // Shaded (relocated) to avoid classpath conflicts with other mods bundling the same libs.
    shaded("org.mongodb:mongodb-driver-sync:${property("mongodb_version")}")
    shaded("org.mongodb:mongodb-driver-core:${property("mongodb_version")}")
    shaded("org.mongodb:bson:${property("mongodb_version")}")
    shaded("org.spongepowered:configurate-hocon:${property("hocon_version")}")
}

tasks.shadowJar {
    archiveClassifier.set("dev-shadow")
    configurations = listOf(shaded)

    // Rewrite META-INF/services/* to match the relocations; FML rejects jars with dangling provider entries.
    mergeServiceFiles()

    relocate("org.spongepowered.configurate", "xyz.nikitacartes.shadow.configurate")
    relocate("com.typesafe.config", "xyz.nikitacartes.shadow.config")
    relocate("io.leangen.geantyref", "xyz.nikitacartes.shadow.geantyref")
    relocate("net.kyori.option", "xyz.nikitacartes.shadow.option")
    relocate("org.bson", "xyz.nikitacartes.shadow.bson")
    relocate("com.mongodb", "xyz.nikitacartes.shadow.mongodb")

    from(sourceSets.main.get().output)
}

tasks.jar {
    from("LICENSE")
    dependsOn(tasks.shadowJar)
    from(zipTree(tasks.shadowJar.get().archiveFile)) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    val mainClassesDirs = sourceSets.main.get().output.classesDirs.files
    exclude { element ->
        element.file.extension == "class" && mainClassesDirs.any { element.file.toPath().startsWith(it.toPath()) }
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    // Ship only the version-correct access transformer, at the canonical path neoforge.mods.toml references.
    exclude("accesstransformer/**", "accesswidener/**", "easyauth.mixins.json.license")
    from(atSource) {
        into("META-INF")
        rename { "accesstransformer.cfg" }
    }

    val expansions = mapOf(
        "version" to dynamicVersion,
        "supported_minecraft_version" to project.property("supported_minecraft_version"),
        "minecraft_version" to project.property("minecraft_version"),
        "neoforge_version" to project.property("neoforge_version"),
        "mod_id" to project.property("mod_id"),
        "mod_name" to project.property("mod_name")
    )
    inputs.properties(expansions)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(expansions)
    }

    // No refmap on official Mojang names; blank the Fabric placeholder.
    filesMatching("easyauth.mixins.json") {
        filter { it.replace("\${refmap}", "") }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}


// Make createMinecraftArtifacts wait for Stonecutter generation (clean first build).
tasks.named("createMinecraftArtifacts") {
    dependsOn(tasks.named("stonecutterGenerate"))
}

tasks.register<Copy>("collectJars") {
    group = "build"
    from(tasks.jar.map { it.archiveFile })
    into(rootProject.layout.buildDirectory.file("libs"))
    dependsOn("build")
}

publishMods {
    val modrinthToken = System.getenv("MODRINTH_TOKEN") ?: ""
    val curseforgeToken = System.getenv("CURSEFORGE_TOKEN") ?: ""

    file = tasks.jar.get().archiveFile
    dryRun = modrinthToken.isEmpty() || curseforgeToken.isEmpty()

    displayName = "${property("display_name")} $dynamicVersion"
    version = dynamicVersion

    changelog = rootProject.file("RELEASE_NOTE.md").readText()
    type = STABLE
    modLoaders.add("neoforge")

    val targets = property("supported_versions").toString()

    modrinth {
        projectId = "aZj58GfX"
        accessToken = modrinthToken
        minecraftVersionList(targets)
        optional("luckperms")
    }

    curseforge {
        projectId = "503866"
        accessToken = curseforgeToken
        minecraftVersionList(targets)
        optional("luckperms")

        server.set(true)
        client.set(false)
    }
}

fun runGit(vararg args: String): String? = try {
    val proc = ProcessBuilder("git", *args).redirectErrorStream(true).start()
    proc.waitFor(5, TimeUnit.SECONDS)
    if (proc.exitValue() == 0) proc.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() } else null
} catch (_: Exception) { null }
