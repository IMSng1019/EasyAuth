plugins { java }

repositories { mavenCentral() }

// Exercise the identity and HTTP trust boundaries without bootstrapping Minecraft.
// The real Fabric build and GameTests separately verify runtime/mixin integration.
sourceSets {
    main {
        java.setSrcDirs(listOf("../../src/main/java"))
        java.include("xyz/nikitacartes/easyauth/sources/**")
        java.include("xyz/nikitacartes/easyauth/skins/**")
        java.exclude("**/MultiAuth.java", "**/LegacyIdentityGuard.java", "**/ServerSkinBridge.java")
    }
    test {
        java.setSrcDirs(listOf("../../src/test/java"))
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
