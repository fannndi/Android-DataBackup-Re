plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.library.test)
}

android {
    namespace = "com.xayah.core.util"
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":core:model"))

    // Compose Navigation
    implementation(libs.androidx.navigation.compose)

    // Gson
    implementation(libs.gson)

    // libsu
    implementation(libs.libsu.core)

    // Shizuku
    implementation(libs.shizuku.api)

    // ADB mandiri: klien ADB di dalam aplikasi, dipakai saat Shizuku tidak ada.
    // bcprov-jdk15to18 dibuang supaya tidak bentrok dengan bcprov-jdk18on yang
    // sudah dipakai aplikasi (bcutil-jdk18on).
    implementation(libs.libadb.android) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation(libs.bouncycastle.bcpkix)

    // zip4j
    implementation(libs.zip4j)

    // Apache commons codec
    implementation(libs.apache.commons.codec)

    // Work manager
    implementation(libs.androidx.work.runtime.ktx)
}
