plugins { id("salud360.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
        }
    }
}
