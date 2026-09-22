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
        // Sube SIEMPRE versionCode en cada release nueva que se publique (aunque el nombre del
        // release en GitHub se quede generico) - es lo unico que Android mira para decidir si
        // una instalacion es "actualizar" (reemplaza, conserva el save) o "ya la tienes" (la
        // rechaza) al descargar el APK de nuevo. Se quedo fijo en 1 durante toda una sesion
        // entera de cambios por descuido - bug real detectado antes de que llegara a afectar a
        // nadie que ya lo tuviera instalado.
        versionCode = 2
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