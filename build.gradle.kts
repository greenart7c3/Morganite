// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.gradle.ktlint) version(libs.versions.ktlint) apply false
}

tasks.register<Copy>("installGitHook") {
    from(File(rootProject.rootDir, "git-hooks/pre-commit"))
    from(File(rootProject.rootDir, "git-hooks/pre-push"))
    into(File(rootProject.rootDir, ".git/hooks"))
    filePermissions {
        unix(0b111101101)
    }
}


tasks.getByPath(":app:preBuild").dependsOn(tasks.getByName("installGitHook"))