pluginManagement {
    repositories {
        // Optional local Maven mirror (works around flaky large downloads on the build host).
        val localMirror = java.io.File(System.getProperty("user.home"), ".gradle/local-mirror")
        if (localMirror.isDirectory) maven { url = localMirror.toURI() }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val localMirror = java.io.File(System.getProperty("user.home"), ".gradle/local-mirror")
        if (localMirror.isDirectory) maven { url = localMirror.toURI() }
        google()
        mavenCentral()
    }
}
rootProject.name = "MATV"
include(":app")
