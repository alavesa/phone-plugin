import java.security.MessageDigest

plugins {
    java
}

group = "fi.alavesa"
version = "1.2.0"

repositories {
    mavenCentral()
    // Paper's 26.x API is published here as <mc>.build.<n>-<channel>; there is no -R0.1-SNAPSHOT any more.
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Pinned to an exact stable build for reproducible builds. Bump the build number to move forward.
    compileOnly("io.papermc.paper:paper-api:26.3.build.253-stable")
    // The server's own netty, compile-only: same-port pack delivery (resourcepack/SamePortHttp) hooks the
    // Minecraft listening channel. Nothing is shaded.
    compileOnly(fileTree("libs") { include("netty-*.jar") })

    testImplementation("io.papermc.paper:paper-api:26.3.build.253-stable")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    // Paper 26.3 requires Java 25. --release 25 lets any JDK >= 25 build it (no toolchain download needed).
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

// ---------------------------------------------------------------------------------------------------------------------
// Resource pack: zip resourcepack/ reproducibly (same input = same bytes = same SHA-1) and write the hash next to it.
//   ./gradlew packZip      -> build/pack/phone-pack.zip + build/pack/phone-pack.zip.sha1
// The zip also rides inside the plugin jar; the plugin unpacks it to plugins/Phone/phone-pack.zip on start.
// ---------------------------------------------------------------------------------------------------------------------
val packZip by tasks.registering(Zip::class) {
    group = "build"
    description = "Zips the resource pack and prints its SHA-1"
    from("resourcepack")
    exclude("**/.DS_Store")
    archiveFileName.set("phone-pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("pack"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doLast {
        val zip = archiveFile.get().asFile
        val sha1 = MessageDigest.getInstance("SHA-1").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        File(zip.parentFile, zip.name + ".sha1").writeText(sha1 + "\n")
        println("Resource pack: $zip")
        println("SHA-1:         $sha1")
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
    from(packZip) { into("pack") }
}
