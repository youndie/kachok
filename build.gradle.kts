plugins {
    // Declared here with `apply false` so the versions are named once and the modules ask by bare id.
    // Asking for a version in a module as well is refused when the root applies a plugin from the
    // same jar: "plugin is already on the classpath with an unknown version".
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.sborkaBase) apply false
    alias(libs.plugins.sborkaTest) apply false
    alias(libs.plugins.sborkaKmp) apply false
    alias(libs.plugins.sborkaLint) apply false
}
