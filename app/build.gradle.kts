import com.android.build.api.dsl.Packaging

plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.ppp.svwbrecord"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ppp.svwbrecord"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // コマンドラインから 'target' プロパティを取得。指定がなければ "self" をデフォルトにする
        val target = project.findProperty("target")?.toString() ?: "self"

        // 'target' の値に応じて、使用するプロパティ名を決定
        val spreadsheetIdProperty = if (target == "friends") {
            "MY_SPREADSHEET_ID_FOR_FRIENDS"
        } else {
            "MY_SPREADSHEET_ID_FOR_SELF"
        }

        // 決定したプロパティ名で gradle.properties からIDを取得
        val spreadsheetId = project.findProperty(spreadsheetIdProperty) ?: "YOUR_DEFAULT_ID"
        val sharedPassword = project.findProperty("SHARED_PASSWORD") ?: "DEF_PASSWORD"


        // BuildConfigにスプレッドシートIDを設定
        buildConfigField("String", "SPREADSHEET_ID", "\"$spreadsheetId\"")
        buildConfigField("String", "SHARED_PASSWORD","\"$sharedPassword\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.google.http.client)
    implementation(libs.google.api.client.gson)
    implementation(libs.google.api.services.sheets)
    implementation(libs.google.android.gms.play.services.auth)
    implementation(libs.google.api.client.android)
    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}