plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName.set(properties["archives_base_name"] as String)
    version.set(libs.versions.mod.version.get())
    group.set(properties["maven_group"] as String)
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.meteorclient.com/releases") }
    maven { url = uri("https://jitpack.io") }
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    compileOnly("com.github.cabaletta:baritone:1.21-SNAPSHOT")

    // Fabric
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)

    // Meteor
    implementation(libs.meteor.client)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
    }
}

fun toMinecraftCompat(version: String): String {
    val match = Regex("""^(\d{2})\.([1-9]\d*)(?:\.([1-9]\d*))?$""").matchEntire(version)
        ?: error("Invalid Minecraft version format: ${version}. Expected YY.D or YY.D.H")

    val (year, drop, _) = match.destructured
    return "$year.$drop"
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "minecraft_version" to toMinecraftCompat(libs.versions.minecraft.get()),
            "jdk_version" to libs.versions.jdk.get(),
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }
}
