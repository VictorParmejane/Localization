// build.gradle.kts (nível do projeto)
plugins {
    // Plugin padrão do Android, vindo do version catalog
    alias(libs.plugins.android.application) apply false

    // 🔹 Plugin do Google Services (Firebase)
    id("com.google.gms.google-services") version "4.4.4" apply false
}