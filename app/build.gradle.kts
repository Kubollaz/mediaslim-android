plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Klucz podpisu lezy w repozytorium jako tekst (klucz/mediaslim.jks.b64),
// a workflow rozpakowuje go przed budowaniem. Dzieki temu kazda wersja jest
// podpisana tym samym kluczem i instaluje sie na poprzedniej, bez odinstalowania.
val plikKlucza = rootProject.file("klucz/mediaslim.jks")

android {
    namespace = "pl.mediaslim"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.mediaslim"
        minSdk = 30
        targetSdk = 35
        // Numer budowania z GitHub Actions - kazda wersja ma wyzszy numer,
        // inaczej Android odmawia aktualizacji.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("wydanie") {
            if (plikKlucza.exists()) {
                storeFile = plikKlucza
                storePassword = "mediaslim"
                keyAlias = "mediaslim"
                keyPassword = "mediaslim"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (plikKlucza.exists()) {
                signingConfigs.getByName("wydanie")
            } else {
                signingConfigs.getByName("debug")
            }
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

    // Media3 Transformer oznacza czesc API jako niestabilne; to ostrzezenie
    // lintu, nie blad kompilacji - nie moze zatrzymywac budowania.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val compose = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-common:1.9.0")
    implementation("androidx.media3:media3-transformer:1.9.0")
    implementation("androidx.media3:media3-effect:1.9.0")
    implementation("androidx.media3:media3-container:1.9.0")
}
