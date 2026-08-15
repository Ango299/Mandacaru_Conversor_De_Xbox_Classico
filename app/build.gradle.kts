import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.seunome.xisoconverter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.seunome.xisoconverter"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            // As libs .so compiladas pelo Rust/cargo-ndk caem aqui.
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // Ícones extras (Folder, Description, CheckCircle, etc.) usados na aba
    // "Como Usar" para ilustrar cada passo do tutorial.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    // LifecycleService: dá um coroutineScope pronto para o ConversionService
    // rodar a conversão em primeiro plano.
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
}

// ---------------------------------------------------------------------------
// Integração com a biblioteca nativa em Rust (xdvdfs-core via JNI)
//
// Isso chama "cargo ndk" para compilar rust/xdvdfs-jni para as ABIs Android
// e coloca os .so resultantes em app/src/main/jniLibs/<abi>/libxdvdfs_jni.so,
// de onde o AGP os empacota automaticamente no APK.
//
// Pré-requisitos (ver LEIA-ME.md):
//   1) Rust instalado (rustup)
//   2) cargo install cargo-ndk
//   3) rustup target add aarch64-linux-android armv7-linux-androideabi \
//        x86_64-linux-android i686-linux-android
//   4) ANDROID_NDK_HOME / ndk.dir configurado (o próprio Android Studio
//      já instala o NDK; aponte para a pasta em local.properties)
// ---------------------------------------------------------------------------

val rustDir = rootProject.file("rust")
val jniLibsDir = file("src/main/jniLibs")
val cargoBin: String = (project.findProperty("XISO_CARGO_BIN") as String?) ?: "cargo"

val cargoNdkAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val buildRustNativeLib = tasks.register<Exec>("buildRustNativeLib") {
    group = "build"
    description = "Compila a biblioteca nativa xdvdfs-jni (Rust) para Android via cargo-ndk"

    workingDir = rustDir
    jniLibsDir.mkdirs()

    val cmd = mutableListOf(cargoBin, "ndk")
    for (abi in cargoNdkAbis) {
        cmd.add("-t")
        cmd.add(abi)
    }
    cmd.add("-o")
    cmd.add(jniLibsDir.absolutePath)
    cmd.add("build")
    cmd.add("--release")
    cmd.add("--package")
    cmd.add("xdvdfs-jni")

    commandLine(cmd)

    // Não quebra a configuração do projeto se o Rust ainda não estiver
    // instalado; o erro só aparece ao tentar de fato compilar/rodar o app.
    isIgnoreExitValue = false
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(buildRustNativeLib)
}
