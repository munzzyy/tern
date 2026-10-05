plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.munzzyy.tern"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.munzzyy.tern"
        minSdk = 28
        targetSdk = 36
        versionCode = 301
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo.include = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en", "ar", "bs", "ca", "cs", "da", "de", "eo", "es", "fa", "fr", "gl", "hu", "in", "it", "ja", "ko", "ml",
            "nl", "pl", "pt", "pt-rBR", "ru", "sv", "tr", "uk", "vi", "zh-rCN", "zh-rTW",
        )
    }

    lint {
        // A string a translation lacks is shown in English; tools/strings.py checks the rest.
        disable += "MissingTranslation"
        // core is plain Java code that runs on Android: lint has to read it against this module's minSdk.
        checkDependencies = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// BrandTest and ConsentTest read these from disk, so a change to one of them has to run the unit tests again.
tasks.withType<Test>().configureEach {
    inputs.files(
        "src/main/AndroidManifest.xml",
        "src/main/res/values/colors.xml",
        "src/main/res/values-night/colors.xml",
        "src/main/res/drawable/ic_launcher_foreground.xml",
        "src/main/res/drawable/ic_launcher_monochrome.xml",
        "src/main/res/mipmap-anydpi/ic_launcher.xml",
        "src/main/res/drawable-xhdpi/banner.png",
    ).withPropertyName("brandFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    // SignerJudgeTest reads the parts of the test bundle from core.
    inputs.dir("../core/src/test/resources/fixtures/apk").withPropertyName("bundleParts").withPathSensitivity(PathSensitivity.RELATIVE)
    // NetworkSecurityConfigTest reads the configs and looks for certificates anywhere under res.
    inputs.dir("src/main/res").withPropertyName("resources").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Installs without a prompt where the user runs Shizuku or Sui. Only its binder is used, never the network.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Opens to Tern the parts of Android's installer that installing through Dhizuku takes, and no others.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
