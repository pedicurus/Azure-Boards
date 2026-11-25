// Used only by the GitHub workflows, never by local or Google AI Studio builds.
// Maven Central rate-limits GitHub's shared runners (HTTP 429), which broke CI downloads of large
// Robolectric jars. Google's official mirror of Maven Central serves the same files, so try it first;
// anything it lacks (for example androidx) still comes from the repositories in settings.gradle.kts.
val mavenCentralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

beforeSettings {
  pluginManagement.repositories { maven(mavenCentralMirror) { name = "MavenCentralGoogleMirror" } }
  dependencyResolutionManagement.repositories {
    maven(mavenCentralMirror) { name = "MavenCentralGoogleMirror" }
  }
}

// Robolectric downloads its Android runtime jar itself at test time, outside Gradle.
allprojects {
  tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", mavenCentralMirror)
    systemProperty("robolectric.dependency.repo.id", "maven-central-google-mirror")
  }
}
