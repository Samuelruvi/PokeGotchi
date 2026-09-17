plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.pokegotchi"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.pokegotchi"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // Decodificacion de GIF en el dispositivo (extraer frames de los sprites Gen 5)
    implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.29")
    // Pokedex: lista y carga de miniaturas
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.6.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}