plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.shinpstudio.memorymanager"
    compileSdk = 35
    defaultConfig { applicationId = "com.shinpstudio.memorymanager"; minSdk = 30; targetSdk = 35; versionCode = 4; versionName = "0.3.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources { excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/module-info.class") } }
    sourceSets.getByName("test").java.srcDir("../tests")
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
