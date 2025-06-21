plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.graduateproject"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.graduateproject"
        minSdk = 24
        targetSdk = 35
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
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // ✅ OkHttp 의존성 추가 (올바른 형식)
    implementation(libs.okhttp)
    //implementation("io.github.controlwear:virtualjoystick:1.10.1")
    implementation ("com.google.android.material:material:1.12.0")
}