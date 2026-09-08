plugins { id("salud360.kmp.library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.serialization.json)
        }
    }
}
