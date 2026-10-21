pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "altscodex-aos-sdk"
include(":sdk")
include(":sample") // 데모 앱. JitPack 배포 대상 아님(sdk 만 publish).
