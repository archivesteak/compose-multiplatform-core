/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.androidx.build

import org.gradle.api.DomainObjectCollection
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.ZipEntryCompression

/**
 * Adds a small, reproducible documentation artifact to every publication that has a primary
 * artifact. The dedicated input is LF-normalized by `.gitattributes`, so this shared artifact is
 * byte-identical across producer hosts. Publications without an unclassified artifact are
 * intentional POM-only redirects and must remain POM-only.
 */
internal fun Project.configureReadmeJavadocArtifacts(
    publications: DomainObjectCollection<MavenPublication>
) {
    val readmeJavadocJar =
        tasks.register("readmeJavadocJar", Jar::class.java) { task ->
            task.group = "publishing"
            task.description = "Packages deterministic Maven Central documentation"
            task.archiveClassifier.set(JAVADOC_CLASSIFIER)
            task.destinationDirectory.set(layout.buildDirectory.dir("publications/readme-javadoc"))
            task.duplicatesStrategy = DuplicatesStrategy.FAIL
            task.isPreserveFileTimestamps = false
            task.isReproducibleFileOrder = true
            task.entryCompression = ZipEntryCompression.STORED
            task.filePermissions { permissions -> permissions.unix("0644") }
            task.dirPermissions { permissions -> permissions.unix("0755") }
            task.from(rootProject.layout.projectDirectory.file(JAVADOC_README_PATH))
        }

    tasks.withType(AbstractPublishToMaven::class.java).configureEach { publishTask ->
        publishTask.dependsOn(readmeJavadocJar)
    }
    gradle.projectsEvaluated { attachReadmeJavadocArtifacts(publications, readmeJavadocJar) }
}

internal fun Project.attachReadmeJavadocArtifacts(
    publications: DomainObjectCollection<MavenPublication>,
    readmeJavadocJar: TaskProvider<Jar>,
) {
    publications.configureEach { publication ->
        publication.artifacts.all { artifact ->
            if (
                artifact.classifier.isNullOrBlank() &&
                    publication.artifacts.none { it.classifier == JAVADOC_CLASSIFIER }
            ) {
                publication.artifact(readmeJavadocJar) { javadocArtifact ->
                    javadocArtifact.classifier = JAVADOC_CLASSIFIER
                    javadocArtifact.extension = "jar"
                    javadocArtifact.builtBy(readmeJavadocJar)
                }
            }
        }
    }
}

internal fun effectiveMavenDescription(
    configuredDescription: String?,
    configuredName: String?,
    projectName: String,
): String =
    configuredDescription?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Compose Multiplatform publication for " +
            (configuredName?.trim()?.takeIf { it.isNotEmpty() } ?: projectName) +
            "."

internal const val FORK_PROJECT_URL = "https://github.com/archivesteak/compose-multiplatform-core"
internal const val FORK_SCM_CONNECTION =
    "scm:git:https://github.com/archivesteak/compose-multiplatform-core.git"
internal const val FORK_SCM_DEVELOPER_CONNECTION =
    "scm:git:ssh://git@github.com/archivesteak/compose-multiplatform-core.git"
internal const val FORK_DEVELOPER_ID = "archivesteak"
internal const val FORK_DEVELOPER_NAME = "Jack Harrington"
internal const val FORK_DEVELOPER_URL = "https://github.com/archivesteak"
internal const val JAVADOC_README_PATH =
    "buildSrc/private/src/main/resources/maven-central-javadoc/README.md"
private const val JAVADOC_CLASSIFIER = "javadoc"
