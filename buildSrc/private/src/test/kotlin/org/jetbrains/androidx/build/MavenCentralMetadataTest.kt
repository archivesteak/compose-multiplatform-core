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

import java.io.File
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.ZipEntryCompression
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MavenCentralMetadataTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun descriptionsAreTrimmedAndBlankDescriptionsReceiveAFallback() {
        assertEquals(
            "Configured description",
            effectiveMavenDescription("  Configured description  ", "Compose Desktop", "desktop"),
        )
        assertEquals(
            "Compose Multiplatform publication for Compose Desktop.",
            effectiveMavenDescription(" \n ", " Compose Desktop ", "desktop"),
        )
        assertEquals(
            "Compose Multiplatform publication for desktop.",
            effectiveMavenDescription(null, "  ", "desktop"),
        )
    }

    @Test
    fun forkCoordinatesIdentifyThePublishedSourceAndDeveloper() {
        assertEquals("https://github.com/archivesteak/compose-multiplatform-core", FORK_PROJECT_URL)
        assertEquals(
            "scm:git:https://github.com/archivesteak/compose-multiplatform-core.git",
            FORK_SCM_CONNECTION,
        )
        assertEquals(
            "scm:git:ssh://git@github.com/archivesteak/compose-multiplatform-core.git",
            FORK_SCM_DEVELOPER_CONNECTION,
        )
        assertEquals("archivesteak", FORK_DEVELOPER_ID)
        assertEquals("Jack Harrington", FORK_DEVELOPER_NAME)
        assertEquals("https://github.com/archivesteak", FORK_DEVELOPER_URL)
    }

    @Test
    fun readmeJavadocIsReproducibleAndPomOnlyPublicationStaysArtifactFree() {
        val projectDir = temporaryFolder.newFolder("project")
        val readme =
            File(projectDir, JAVADOC_README_PATH).apply {
                parentFile.mkdirs()
                writeText("# Test documentation\n")
            }
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.pluginManager.apply("maven-publish")
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        val publications = publishing.publications.withType(MavenPublication::class.java)
        val pomOnly = publishing.publications.create("pomOnly", MavenPublication::class.java)
        val binary = publishing.publications.create("binary", MavenPublication::class.java)

        assertTrue(pomOnly.artifacts.isEmpty())
        assertTrue(binary.artifacts.isEmpty())

        binary.artifact(temporaryFolder.newFile("binary.jar"))
        binary.artifact(temporaryFolder.newFile("binary-secondary.jar"))

        project.configureReadmeJavadocArtifacts(publications)
        val task = project.tasks.named("readmeJavadocJar", Jar::class.java)
        project.attachReadmeJavadocArtifacts(publications, task)

        val javadocArtifact = binary.artifacts.single { it.classifier == "javadoc" }
        assertTrue(pomOnly.artifacts.isEmpty())

        val jarTask = task.get()
        assertEquals("javadoc", jarTask.archiveClassifier.get())
        assertFalse(jarTask.isPreserveFileTimestamps)
        assertTrue(jarTask.isReproducibleFileOrder)
        assertEquals(ZipEntryCompression.STORED, jarTask.entryCompression)
        assertEquals(420, jarTask.filePermissions.get().toUnixNumeric())
        assertEquals(493, jarTask.dirPermissions.get().toUnixNumeric())
        assertTrue(
            jarTask.source.files
                .map { sourceFile -> sourceFile.canonicalFile }
                .contains(readme.canonicalFile)
        )
        assertTrue(javadocArtifact.buildDependencies.getDependencies(jarTask).contains(jarTask))
        val publishTask = project.tasks.named("publishBinaryPublicationToMavenLocal").get()
        assertTrue(publishTask.taskDependencies.getDependencies(publishTask).contains(jarTask))
    }
}
