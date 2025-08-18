import java.util.Properties

// Load Bitvavo creds from bitvavo.properties
val bitvavoProps = Properties().apply {
    val f = rootProject.file("bitvavo.properties")
    if (f.exists()) load(f.inputStream())
}
val bitvavoApiKey    = bitvavoProps.getProperty("BITVAVO_API_KEY", "")
val bitvavoApiSecret = bitvavoProps.getProperty("BITVAVO_API_SECRET", "")

// Load Trading212 API key from trading212.properties
val trading212Props = Properties().apply {
    val f = rootProject.file("trading212.properties")
    if (f.exists()) load(f.inputStream())
}
val trading212ApiKey = trading212Props.getProperty("TRADING212_API_KEY", "")


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}



android {
    namespace = "com.spymag.portfoliowidget"
    compileSdk = 36
    android.buildFeatures.buildConfig = true

    defaultConfig {
        if (bitvavoApiKey.isEmpty() || bitvavoApiSecret.isEmpty()) {
            throw GradleException("Define BITVAVO_API_KEY/SECRET in bitvavo.properties")
        }
        if (trading212ApiKey.isEmpty()) {
            throw GradleException("Define TRADING212_API_KEY in trading212.properties")
        }
        buildConfigField("String","BITVAVO_API_KEY",  "\"$bitvavoApiKey\"")
        buildConfigField("String","BITVAVO_API_SECRET","\"$bitvavoApiSecret\"")
        buildConfigField("String","TRADING212_API_KEY","\"$trading212ApiKey\"")

        applicationId = "com.spymag.portfoliowidget"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // Consider moving these to the version catalog (libs.versions.toml)
    implementation("com.squareup.okhttp3:okhttp:4.11.0") // Newer version 5.1.0 available
    implementation("org.json:json:20230227")            // Newer version 20250517 available
}
