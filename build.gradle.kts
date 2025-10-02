import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.7.2"
    id("com.google.protobuf") version "0.9.5"
}

group = "com.perforator"
version = "1.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.google.protobuf:protobuf-java:3.24.3")

    intellijPlatform {
        // IntelliJ IDEA Community 2025.1.3
        create("IC", "2025.1.3")

        // Bundled Java plugin for Java PSI/APIs
        bundledPlugin("com.intellij.java")

        // Useful platform tooling
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Perforator"
        version = project.version.toString()
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Patch plugin.xml build range
    patchPluginXml {
        sinceBuild.set("251.26094.121")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.24.3"
    }
    // No deprecated generatedFilesBaseDir setting used
}

sourceSets {
    main {
        proto {
            srcDir("src/main/proto/google/pprof/proto") // Your proto files location
        }
        java {
            srcDir("src/generated/main/java") // Generated Java source here
        }
    }
}