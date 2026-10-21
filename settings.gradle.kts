pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "altscodex-aos-sdk"
include(":sdk")
// include(":sample") // W2 단계에서 데모 앱 모듈을 추가한다(JitPack 배포 대상 아님).
