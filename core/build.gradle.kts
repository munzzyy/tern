import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.android.lint")
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
    // CatalogTest reads these from disk, so a change to one of them has to run the tests again.
    inputs.files("../docs/SUGGESTIONS.md", "../app/src/main/res/values/strings_suggest.xml")
        .withPropertyName("catalogFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("stamp.live", System.getProperty("stamp.live") ?: "false")
    systemProperty("stamp.index", System.getProperty("stamp.index") ?: "")
    testLogging.showStandardStreams = System.getProperty("stamp.live") == "true" || System.getProperty("stamp.index") != null
}
