plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Unit-test coverage with Gradle's own JaCoCo plugin (no added dependency). Every JVM test task runs with the agent;
// `jacocoUnitTestReport` in each module writes XML + CSV for its own classes, crediting any module's tests that ran
// them (every module's .exec is read). Run it after the tests, in its own invocation. Reported, never a gate:
// docs/TESTING.md › Coverage. tools/test-report reads the CSVs.
subprojects {
    fun wireCoverage(android: Boolean) {
        apply(plugin = "jacoco")
        extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.13" }
        tasks.withType<Test>().configureEach {
            extensions.configure<JacocoTaskExtension> {
                // Robolectric loads the classes under test through its own class loader.
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
        val root = rootProject.layout.projectDirectory
        tasks.register<JacocoReport>("jacocoUnitTestReport") {
            group = "verification"
            description = "Unit-test coverage of this module's classes (XML + CSV)."
            executionData.from(
                rootProject.fileTree(root) { include("core/*/build/jacoco/*.exec", "feature/*/build/jacoco/*.exec", "app/build/jacoco/*.exec") },
            )
            val classes = if (android) "tmp/kotlin-classes/debug" else "classes/kotlin/main"
            classDirectories.from(
                fileTree(layout.buildDirectory.dir(classes)) {
                    exclude(
                        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/*_Factory*", "**/*_HiltModules*", "**/Hilt_*",
                        "**/*_MembersInjector*", "**/*_Impl*", "**/dagger/**", "**/hilt_aggregated_deps/**", "**/*ComposableSingletons*",
                    )
                },
            )
            sourceDirectories.from(files("src/main/kotlin", "src/main/java"))
            reports {
                xml.required.set(true)
                csv.required.set(true)
                html.required.set(false)
            }
        }
    }
    pluginManager.withPlugin("com.android.library") { wireCoverage(android = true) }
    pluginManager.withPlugin("com.android.application") { wireCoverage(android = true) }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { wireCoverage(android = false) }
}
