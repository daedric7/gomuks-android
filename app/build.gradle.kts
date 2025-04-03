plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.gms.google.services)
    id("kotlin-kapt") // Add this line for annotation processing
}

android {
    namespace = "app.gomuks.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "pt.aguiarvieira.gomuks"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("releaseSigning") {
            keyAlias = "habitica"         // Alias of the key in the keystore
            keyPassword = "12345678"   // Password for the key
            storeFile = file("./gomuks.keystore")  // Keystore file path
            storePassword = "12345678"  // Keystore password
        }
    } 

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("releaseSigning")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    splits {
        abi {
            isEnable = true // Corrected: 'isEnable' instead of 'enable'
            reset()
            include("arm64-v8a", "armeabi-v7a") // Only include needed ABIs
            isUniversalApk = false // Corrected: 'isUniversalApk' instead of 'universalApk'
        }
    }
}

dependencies {
    val activity_version = "1.10.1"
    implementation(libs.geckoview.beta)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("androidx.fragment:fragment-ktx:1.6.2") // Use the latest version
    implementation("org.snakeyaml:snakeyaml-engine:2.7") // Use latest version
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core:1.8.0") // Add the core library for NotificationCompat
    implementation("androidx.activity:activity-ktx:$activity_version") //Edge to edge
    implementation("androidx.interpolator:interpolator:1.0.0")

    // Glide dependencies
    implementation("com.github.bumptech.glide:glide:4.14.2")
    kapt("com.github.bumptech.glide:compiler:4.14.2") // Add this line for annotation processing
    
    // OkHttp dependencies
    implementation("com.squareup.okhttp3:okhttp:4.9.3") // Add OkHttp dependency
}


configurations.all {
    exclude(group = "org.yaml", module = "snakeyaml")
}
