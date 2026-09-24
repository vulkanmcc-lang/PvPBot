plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "com.pvpbot"
version = "1.1.5"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")

    implementation("de.maxhenkel.voicechat:voicechat-api:2.6.24")
}

tasks {
    assemble {
        dependsOn(reobfJar)
    }

    jar {
        archiveFileName.set("PvPBot-${project.version}.jar")
    }
}