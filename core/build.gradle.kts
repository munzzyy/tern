import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    systemProperty("jackdaw.live", System.getProperty("jackdaw.live") ?: "false")
    systemProperty("jackdaw.index", System.getProperty("jackdaw.index") ?: "")
    testLogging.showStandardStreams = System.getProperty("jackdaw.live") == "true" || System.getProperty("jackdaw.index") != null
}
