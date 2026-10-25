plugins {
    id("java")
}

group = "es.mrdino"
version = "1.8.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.30.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.lwjgl:lwjgl:3.3.3")
    testImplementation("org.lwjgl:lwjgl-glfw:3.3.3")
    testImplementation("org.lwjgl:lwjgl-opengl:3.3.3")
    testImplementation("org.lwjgl:lwjgl-shaderc:3.3.3")
    testRuntimeOnly("org.lwjgl:lwjgl:3.3.3:natives-windows")
    testRuntimeOnly("org.lwjgl:lwjgl-glfw:3.3.3:natives-windows")
    testRuntimeOnly("org.lwjgl:lwjgl-opengl:3.3.3:natives-windows")
    testRuntimeOnly("org.lwjgl:lwjgl-shaderc:3.3.3:natives-windows")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

val pluginVersion = project.version.toString()
tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion, "apiVersion" to "1.21.4")
    }
}

data class MinecraftBranch(val name: String, val api: String, val versions: List<String>, val format: Int, val minor: Int = 0)
val minecraftBranches = listOf(
    MinecraftBranch("1.21.X", "1.21.4", (4..11).map { "1.21.$it" }, 46),
    MinecraftBranch("26.1.X", "26.1", listOf("26.1", "26.1.1", "26.1.2"), 84),
    MinecraftBranch("26.2.X", "26.2", listOf("26.2"), 88),
    MinecraftBranch("26.3.X", "26.3", listOf("26.3"), 97, 1)
)
val compatibilityMetadata = tasks.register("compatibilityMetadata") {
    inputs.property("version", pluginVersion)
    inputs.file("src/main/resources/plugin.yml")
    outputs.dir(layout.buildDirectory.dir("generated/compatibility"))
    doLast {
        val root = layout.buildDirectory.dir("generated/compatibility").get().asFile
        for (branch in minecraftBranches) {
            val directory = root.resolve(branch.name).apply { mkdirs() }
            val maximum = if (branch.format == 46) 75 else branch.format
            val legacy = if (branch.format == 46) "\"pack_format\":46,\"supported_formats\":[46,75]," else ""
            val minimum = if (branch.minor == 0) "${branch.format}" else "[${branch.format},${branch.minor}]"
            val overlays = if (branch.format == 46) """, "overlays":{"entries":[
                {"directory":"mc_1_21_5","formats":[55,62],"min_format":55,"max_format":62},
                {"directory":"mc_1_21_6","formats":[63,64],"min_format":63,"max_format":64},
                {"directory":"mc_1_21_9","formats":[69,75],"min_format":69,"max_format":75}
            ]}""" else ""
            directory.resolve("pack.mcmeta").writeText("""{"pack":{$legacy"min_format":$minimum,"max_format":$maximum,"description":"FlashLight $pluginVersion - Minecraft ${branch.name}"}$overlays}
""")
            directory.resolve("compatibility.properties").writeText("minecraft.branch=${branch.name}\nminecraft.versions=${branch.versions.joinToString(",")}\n")
            directory.resolve("plugin.yml").writeText(file("src/main/resources/plugin.yml").readText()
                .replace("\${version}", pluginVersion).replace("\${apiVersion}", branch.api))
        }
    }
}

val branchPacks = minecraftBranches.mapIndexed { index, branch ->
    tasks.register<Zip>(if (index == 0) "resourcePack" else "resourcePack" + branch.api.replace('.', '_')) {
        group = "build"
        description = "Build the resource pack for Minecraft ${branch.name}."
        dependsOn(compatibilityMetadata)
        from(layout.buildDirectory.dir("generated/compatibility/${branch.name}")) { include("pack.mcmeta") }
        if (index == 0) {
            from("resource-pack") { exclude("pack.mcmeta") }
            for (profile in listOf("1.21.5", "1.21.6", "1.21.9")) {
                from("resource-pack-compat/$profile") { into("mc_" + profile.replace('.', '_')) }
            }
        } else {
            from("resource-pack") {
                exclude("pack.mcmeta", "assets/minecraft/shaders/**", "assets/minecraft/post_effect/**", "assets/flashlight/shaders/**")
            }
            from("resource-pack-compat/${branch.api}")
        }
        archiveFileName.set("FlashLight-ResourcePack-$pluginVersion-${branch.name}.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
val branchJars = minecraftBranches.mapIndexed { index, branch ->
    tasks.register<Jar>("jar" + branch.api.replace('.', '_')) {
        group = "build"
        description = "Build the plugin for Paper ${branch.name}."
        dependsOn(tasks.classes, branchPacks[index], compatibilityMetadata)
        from(sourceSets.main.get().output) { exclude("plugin.yml") }
        from(layout.buildDirectory.dir("generated/compatibility/${branch.name}")) { include("plugin.yml", "compatibility.properties") }
        from(branchPacks[index].flatMap { it.archiveFile }) {
            into("pack")
            rename { "FlashLight-ResourcePack.zip" }
        }
        archiveFileName.set("FlashLight-$pluginVersion-${branch.name}.jar")
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
tasks.jar { enabled = false }

tasks.processTestResources {
    dependsOn(branchPacks)
    from(branchPacks.first().flatMap { it.archiveFile }) {
        into("pack")
        rename { "FlashLight-ResourcePack.zip" }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.assemble {
    dependsOn(branchPacks, branchJars)
}

val smoke = sourceSets.create("smoke") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}
tasks.register<Jar>("smokeProbe") {
    dependsOn(tasks.classes, tasks.named(smoke.classesTaskName))
    from(smoke.output)
    archiveFileName.set("FlashLight-SmokeProbe.jar")
    destinationDirectory.set(layout.buildDirectory.dir("paper-smoke/plugins"))
}
