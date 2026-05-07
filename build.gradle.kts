plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "com.github.tomdh-git"
version = project.findProperty("version") ?: "master-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux:3.3.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.8.1")
    implementation("org.slf4j:slf4j-api:2.0.16")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
