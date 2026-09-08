plugins { id("salud360.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:data"))
            api(project(":core:ui"))
        }
    }
}
