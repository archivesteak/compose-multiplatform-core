import org.jetbrains.androidx.build.ComposePublishingTask
import org.jetbrains.androidx.build.ComposePlatforms
import org.jetbrains.androidx.build.ComposeProperties
import org.jetbrains.androidx.build.JetBrainsPublication

// this module depends on all other modules info, so we need to initialize them first
(rootProject.allprojects - project).forEach {
    evaluationDependsOn(it.path)
}

val libraryToComponents = JetBrainsPublication.libraryToComponents
val Project.composeComponent get() = JetBrainsPublication.projectPathToComponent[path]

val parsedComposeProperties = ComposeProperties(project)

tasks.register("publishComposeJb", ComposePublishingTask::class) {
    group = "Compose Multiplatform"
    repository = "MavenRepository"
    composeProperties = parsedComposeProperties

    libraries.forEach {
        libraryToComponents[it]?.forEach { publish(rootProject, it) }
    }
}

tasks.register("publishComposeJbToMavenLocal", ComposePublishingTask::class) {
    group = "Compose Multiplatform"
    repository = "MavenLocal"
    composeProperties = parsedComposeProperties

    libraries.forEach {
        libraryToComponents[it]?.forEach { publish(rootProject, it) }
    }
}

// The Windows consumer graph is intentionally explicit. The family aggregate above also includes
// modules and host publications that are not part of the Windows artifact shard, while omitting a
// root publication referenced from a POM makes the fail-closed merger reject the shard.
val windowsAndDesktopClosureProjects = listOf(
    ":compose:animation:animation",
    ":compose:animation:animation-core",
    ":compose:foundation:foundation",
    ":compose:foundation:foundation-layout",
    ":compose:material:material-ripple",
    ":compose:material3:material3",
    ":compose:runtime:runtime",
    ":compose:runtime:runtime-saveable",
    ":compose:ui:ui",
    ":compose:ui:ui-backhandler",
    ":compose:ui:ui-geometry",
    ":compose:ui:ui-graphics",
    ":compose:ui:ui-test",
    ":compose:ui:ui-text",
    ":compose:ui:ui-unit",
    ":compose:ui:ui-util",
    ":navigationevent:navigationevent-compose",
)

val desktopOnlyClosureProjects = listOf(
    ":compose:material:material",
    ":compose:ui:ui-tooling-preview",
)

val desktopDistributionProject = ":compose:desktop:desktop"

// ui-uikit's root is referenced by common POM metadata. Its Apple children belong to the Apple
// shard, but the root must exist here so Maven closure validation remains complete.
val metadataOnlyClosureProjects = listOf(":compose:ui:ui-uikit")

val windowsConsumerClosureProjects =
    windowsAndDesktopClosureProjects +
        desktopOnlyClosureProjects +
        desktopDistributionProject +
        metadataOnlyClosureProjects

// Cross-host shards publish the same Maven-closed KMP graph as Windows, but only for the variants
// that host owns. ui-uikit is an iOS-only consumer root, so it belongs only to the Apple shard.
// Keeping these lists explicit prevents the family-wide publisher from pulling unrelated modules
// (and their independent release trains) into a release just because they share a library family.
val appleConsumerClosureProjects = windowsAndDesktopClosureProjects + metadataOnlyClosureProjects
val webAndroidConsumerClosureProjects = windowsAndDesktopClosureProjects

tasks.register("publishWindowsConsumerClosureToMavenLocal") {
    group = "Compose Multiplatform"
    description = "Publishes the exact Maven-closed JVM and mingwX64 Compose consumer graph."

    windowsConsumerClosureProjects.forEach { projectPath ->
        dependsOn("$projectPath:publishKotlinMultiplatformPublicationToMavenLocal")
        dependsOn("$projectPath:jbVerifyDependencyVersions")
    }

    windowsAndDesktopClosureProjects.forEach { projectPath ->
        dependsOn("$projectPath:publishMingwX64PublicationToMavenLocal")
        dependsOn("$projectPath:publishDesktopPublicationToMavenLocal")
    }

    desktopOnlyClosureProjects.forEach { projectPath ->
        dependsOn("$projectPath:publishDesktopPublicationToMavenLocal")
    }

    dependsOn("$desktopDistributionProject:publishJvmPublicationToMavenLocal")
    dependsOn("$desktopDistributionProject:publishJvmwindows-x64PublicationToMavenLocal")
}

fun registerHostConsumerClosureTask(
    name: String,
    description: String,
    projectPaths: List<String>,
    ownedPlatforms: Set<ComposePlatforms>,
) {
    tasks.register(name, ComposePublishingTask::class) {
        group = "Compose Multiplatform"
        this.description = description
        repository = "MavenLocal"
        composeProperties = parsedComposeProperties

        projectPaths.forEach { projectPath ->
            val component = requireNotNull(JetBrainsPublication.projectPathToComponent[projectPath]) {
                "No registered Compose publication component for $projectPath"
            }
            // The root KMP publication is always emitted. Restrict child publications even if a
            // caller accidentally supplies a broader compose.platforms property.
            publish(
                rootProject,
                component.copy(
                    supportedPlatforms = component.supportedPlatforms.intersect(ownedPlatforms)
                )
            )
        }
    }
}

registerHostConsumerClosureTask(
    name = "publishAppleConsumerClosureToMavenLocal",
    description = "Publishes the exact Maven-closed macOS and iOS Compose consumer graph.",
    projectPaths = appleConsumerClosureProjects,
    ownedPlatforms = setOf(
        ComposePlatforms.MacosArm64,
        ComposePlatforms.IosArm64,
        ComposePlatforms.IosSimulatorArm64,
    ),
)

registerHostConsumerClosureTask(
    name = "publishWebAndroidConsumerClosureToMavenLocal",
    description = "Publishes the exact Maven-closed JS, WasmJS, and Android Compose consumer graph.",
    projectPaths = webAndroidConsumerClosureProjects,
    ownedPlatforms = setOf(
        ComposePlatforms.Js,
        ComposePlatforms.WasmJs,
        ComposePlatforms.Android,
    ),
)

val libraries = project.findProperty("jetbrains.publication.libraries")
    ?.toString()?.split(",")
    ?: libraryToComponents.keys


tasks.register("testDesktop") {
    group = "Compose Multiplatform"
    dependsOn(allTasksForPublishingProjectsWith(name = "desktopTest"))
    dependsOn(allTasksForPublishingProjectsWith(name = "desktopHeadlessTest"))
}

tasks.register("testWeb") {
    group = "Compose Multiplatform"
    dependsOn(testWebJs)
    dependsOn(testWebWasm)
}

val testWebJs = tasks.register("testWebJs") {
    dependsOn(":compose:foundation:foundation:jsTest")
    dependsOn(":compose:material3:material3:jsTest")
    dependsOn(":compose:ui:ui-text:jsTest")
    dependsOn(":compose:ui:ui:jsTest")
    dependsOn(":compose:ui:ui-test:jsTest")
    dependsOn(":navigation:navigation-runtime:jsTest")
}

val testWebWasm = tasks.register("testWebWasm") {
    // TODO: ideally we want to run all wasm tests that are possible but now we deal only with modules that have skikoTests
    dependsOn(":compose:foundation:foundation:wasmJsTest")
    dependsOn(":compose:material3:material3:wasmJsTest")
    dependsOn(":compose:ui:ui-text:wasmJsTest")
    dependsOn(":compose:ui:ui:wasmJsTest")
    dependsOn(":compose:ui:ui-test:wasmJsTest")
    dependsOn(":navigation:navigation-runtime:wasmJsTest")
}

tasks.register("testIos") {
    group = "Compose Multiplatform"
    val suffix = if (System.getProperty("os.arch") == "aarch64") "SimulatorArm64Test" else "X64Test"
    val iosTestSubtaskName = "ios$suffix"

    dependsOn(":compose:runtime:runtime:$iosTestSubtaskName")
    dependsOn(":compose:ui:ui-text:$iosTestSubtaskName")
    dependsOn(":compose:ui:ui:$iosTestSubtaskName")
    dependsOn(":compose:material3:material3:$iosTestSubtaskName")
    dependsOn(":compose:foundation:foundation:$iosTestSubtaskName")
}

tasks.register("testRuntimeNative") {
    group = "Compose Multiplatform"
    dependsOn(":compose:runtime:runtime:macosX64Test")
}

tasks.register("testComposeModules") { // used in https://github.com/JetBrains/androidx/tree/jb-main/.github/workflows
    group = "Compose Multiplatform"
    // TODO: download robolectrict to run ui:ui:test
    // dependsOn(":compose:ui:ui:test")

    dependsOn(":compose:ui:ui-graphics:test")
    dependsOn(":compose:ui:ui-geometry:test")
    dependsOn(":compose:ui:ui-unit:test")
    dependsOn(":compose:ui:ui-util:test")
    dependsOn(":compose:runtime:runtime:test")
    dependsOn(":compose:runtime:runtime-saveable:test")
    dependsOn(":compose:material:material:test")
    dependsOn(":compose:material:material-ripple:test")
    dependsOn(":compose:foundation:foundation:test")
    dependsOn(":compose:animation:animation:test")
    dependsOn(":compose:animation:animation-core:test")
    dependsOn(":compose:animation:animation-core:test")

    // TODO: enable ui:ui-text:test
    // dependsOn(":compose:ui:ui-text:test")
    // compose/out/androidx/compose/ui/ui-text/build/intermediates/tmp/manifest/test/debug/tempFile1ProcessTestManifest10207049054096217572.xml Error:
    // android:exported needs to be explicitly specified for <activity>. Apps targeting Android 12 and higher are required to specify an explicit value for `android:exported` when the corresponding component has an intent filter defined.
}

tasks.register("jbApiDump") {
    group = "Compose Multiplatform"
    dependsOn(apiValidationTasks(suffix = "ApiDump"))
}

tasks.register("jbApiCheck") {
    group = "Compose Multiplatform"
    dependsOn(apiValidationTasks(suffix = "ApiCheck"))
}

fun apiValidationTasks(suffix: String) = buildSet<Task> {
    fun Iterable<Task>.filterComposePlatforms(vararg platforms: ComposePlatforms) =
        filter { task ->
            val project = task.project
            val component = project.composeComponent
            platforms.any {
                component != null
                    && it in component.supportedPlatforms
            }
        }

    fun Iterable<Task>.filterComposePlatforms(platforms: Set<ComposePlatforms>) =
        filterComposePlatforms(*platforms.toTypedArray())

    this += allTasksForPublishingProjectsWith(name = "desktop$suffix")
        .filterComposePlatforms(ComposePlatforms.Desktop)

    this += allTasksForPublishingProjectsWith(name = "android$suffix")
        .filterComposePlatforms(ComposePlatforms.ANDROID)

    val klibPlatforms = if (System.getProperty("os.name") == "Mac OS X") {
        ComposePlatforms.GENERATE_KLIB
    } else {
        ComposePlatforms.GENERATE_KLIB - ComposePlatforms.DARWIN
    }
    this += allTasksForPublishingProjectsWith(name = "klib$suffix")
        .filterComposePlatforms(klibPlatforms)
}

fun allTasksForPublishingProjectsWith(name: String): List<Task> =
    rootProject.subprojects.mapNotNull { project ->
         if (JetBrainsPublication.shouldPublish(project)) {
             project.tasks.findByName(name)
         } else {
             null
         }
    }

