plugins {
   alias(libs.plugins.android.application)
   alias(libs.plugins.kotlin.compose)
}

android {
   namespace = "com.androNSZ"
   compileSdk {
      version = release(36) {
         minorApiLevel = 1
      }
   }

   defaultConfig {
      applicationId = "com.androNSZ"
      minSdk = 31
      targetSdk = 36
      versionCode = 1
      versionName = "1.0"

      testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
      ndk {
         abiFilters += listOf("arm64-v8a", "armeabi-v7a")
      }
      externalNativeBuild {
         cmake {
            cFlags += "-std=c11"
         }
      }
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
   externalNativeBuild {
      cmake {
         path = file("src/main/cpp/CMakeLists.txt")
         version = "3.22.1"
      }
   }
   buildFeatures {
      compose = true
   }
   ndkVersion = "28.2.13676358"
   buildToolsVersion = "36.0.0"
}

dependencies {
   implementation(libs.androidx.core.ktx)
   implementation(libs.androidx.lifecycle.runtime.ktx)
   implementation(libs.androidx.activity.compose)
   implementation(platform(libs.androidx.compose.bom))
   implementation(libs.androidx.compose.ui)
   implementation(libs.androidx.compose.ui.graphics)
   implementation(libs.androidx.compose.ui.tooling.preview)
   implementation(libs.androidx.compose.material3)
   implementation("androidx.compose.material:material-icons-extended:1.7.6")
   implementation("androidx.datastore:datastore-preferences:1.1.1")
   testImplementation(libs.junit)
   androidTestImplementation(libs.androidx.junit)
   androidTestImplementation(libs.androidx.espresso.core)
   androidTestImplementation(platform(libs.androidx.compose.bom))
   androidTestImplementation(libs.androidx.compose.ui.test.junit4)
   debugImplementation(libs.androidx.compose.ui.tooling)
   debugImplementation(libs.androidx.compose.ui.test.manifest)
}