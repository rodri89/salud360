plugins { `kotlin-dsl` }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.plugin.kotlin.multiplatform)
    implementation(libs.plugin.kotlin.serialization)
    implementation(libs.plugin.compose.compiler)
    implementation(libs.plugin.compose)
    implementation(libs.plugin.android)
}
