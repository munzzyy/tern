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
    systemProperty("stamp.live", System.getProperty("stamp.live") ?: "false")
    systemProperty("stamp.index", System.getProperty("stamp.index") ?: "")
    testLogging.showStandardStreams = System.getProperty("stamp.live") == "true" || System.getProperty("stamp.index") != null
}
