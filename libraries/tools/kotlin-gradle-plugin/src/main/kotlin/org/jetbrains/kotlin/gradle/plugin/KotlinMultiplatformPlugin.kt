/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.plugin

import com.android.build.gradle.BaseExtension
import org.gradle.api.*
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.logging.kotlinWarn
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile
import org.jetbrains.kotlin.gradle.utils.SingleWarningPerBuild
import org.jetbrains.kotlin.gradle.utils.androidPluginIds
import java.util.concurrent.atomic.AtomicBoolean

abstract class KotlinPlatformPluginBase(protected val platformName: String) : Plugin<Project> {
    companion object {
        @JvmStatic
        protected inline fun <reified T : Plugin<*>> Project.applyPlugin() {
            pluginManager.apply(T::class.java)
        }
    }
}

open class KotlinPlatformCommonPlugin : KotlinPlatformPluginBase("common") {
    override fun apply(project: Project) {
        warnAboutKotlin12xMppDeprecation(project)
        project.applyPlugin<KotlinCommonPluginWrapper>()
    }
}

const val EXPECTED_BY_CONFIG_NAME = "expectedBy"

const val IMPLEMENT_CONFIG_NAME = "implement"
const val IMPLEMENT_DEPRECATION_WARNING = "The '$IMPLEMENT_CONFIG_NAME' configuration is deprecated and will be removed. " +
        "Use '$EXPECTED_BY_CONFIG_NAME' instead."

open class KotlinPlatformImplementationPluginBase(platformName: String) : KotlinPlatformPluginBase(platformName) {
    private val commonProjects = arrayListOf<Project>()

    private fun configurationsForCommonModuleDependency(project: Project): List<Configuration> =
        listOf(project.configurations.getByName("api"))

    override fun apply(project: Project) {
        warnAboutKotlin12xMppDeprecation(project)

        val implementConfig = project.configurations.create(IMPLEMENT_CONFIG_NAME)
        val expectedByConfig = project.configurations.create(EXPECTED_BY_CONFIG_NAME)

        implementConfig.dependencies.whenObjectAdded {
            if (!implementConfigurationIsUsed) {
                implementConfigurationIsUsed = true
                project.logger.kotlinWarn(IMPLEMENT_DEPRECATION_WARNING)
            }
        }

        listOf(implementConfig, expectedByConfig).forEach { config ->
            config.isTransitive = false

            config.dependencies.whenObjectAdded { dep ->
                if (dep is ProjectDependency) {
                    addCommonProject(dep.dependencyProject, project)

                    // Needed for the projects that depend on this one to recover the common module sources through
                    // the transitive dependency (also, it will be added to the POM generated by Gradle):
                    configurationsForCommonModuleDependency(project).forEach { configuration ->
                        configuration.dependencies.add(dep)
                    }
                } else {
                    throw GradleException("$project '${config.name}' dependency is not a project: $dep")
                }
            }
        }

        val incrementalMultiplatform = PropertiesProvider(project).incrementalMultiplatform ?: true
        project.afterEvaluate {
            project.tasks.withType(AbstractKotlinCompile::class.java).all { task ->
                if (task.incremental && !incrementalMultiplatform) {
                    task.logger.debug("IC is turned off for task '${task.path}' because multiplatform IC is not enabled")
                }
                task.incremental = task.incremental && incrementalMultiplatform
            }
        }
    }

    private var implementConfigurationIsUsed = false

    private fun addCommonProject(commonProject: Project, platformProject: Project) {
        commonProjects.add(commonProject)

        commonProject.whenEvaluated {
            if (!commonProject.pluginManager.hasPlugin("kotlin-platform-common")) {
                throw GradleException(
                    "Platform project $platformProject has an " +
                            "'$EXPECTED_BY_CONFIG_NAME'${if (implementConfigurationIsUsed) "/'$IMPLEMENT_CONFIG_NAME'" else ""} " +
                            "dependency to non-common project $commonProject"
                )
            }

            // Since the two projects may add source sets in arbitrary order, and both may do that after the plugin is applied,
            // we need to handle all source sets of the two projects and connect them once we get a match:
            // todo warn if no match found
            matchSymmetricallyByNames(
                getKotlinSourceSetsSafe(commonProject),
                namedSourceSetsContainer(platformProject)
            ) { commonSourceSet: Named, _ ->
                addCommonSourceSetToPlatformSourceSet(commonSourceSet, platformProject)

                // Workaround for older versions of Kotlin/Native overriding the old signature
                commonProject.convention.findPlugin(JavaPluginConvention::class.java)
                    ?.sourceSets
                    ?.findByName(commonSourceSet.name)
                    ?.let { javaSourceSet ->
                        @Suppress("DEPRECATION")
                        addCommonSourceSetToPlatformSourceSet(javaSourceSet, platformProject)
                    }
            }
        }
    }

    /**
     * Applies [whenMatched] to pairs of items with the same name in [containerA] and [containerB],
     * regardless of the order in which they are added to the containers.
     */
    private fun <A, B> matchSymmetricallyByNames(
        containerA: NamedDomainObjectCollection<out A>,
        containerB: NamedDomainObjectCollection<out B>,
        whenMatched: (A, B) -> Unit
    ) {
        val matchedNames = mutableSetOf<String>()

        fun <T, R> NamedDomainObjectCollection<T>.matchAllWith(other: NamedDomainObjectCollection<R>, match: (T, R) -> Unit) {
            this@matchAllWith.all { item ->
                val itemName = this@matchAllWith.namer.determineName(item)
                if (itemName !in matchedNames) {
                    val otherItem = other.findByName(itemName)
                    if (otherItem != null) {
                        matchedNames += itemName
                        match(item, otherItem)
                    }
                }
            }
        }
        containerA.matchAllWith(containerB) { a, b -> whenMatched(a, b) }
        containerB.matchAllWith(containerA) { b, a -> whenMatched(a, b) }
    }

    protected open fun namedSourceSetsContainer(project: Project): NamedDomainObjectContainer<*> =
        project.kotlinExtension.sourceSets

    protected open fun addCommonSourceSetToPlatformSourceSet(commonSourceSet: Named, platformProject: Project) {
        platformProject.whenEvaluated {
            // At the point when the source set in the platform module is created, the task does not exist
            val platformTasks = platformProject.tasks
                .withType(AbstractKotlinCompile::class.java)
                .filter { it.sourceSetName.get() == commonSourceSet.name } // TODO use strict check once this code is not run in K/N

            val commonSources = getKotlinSourceDirectorySetSafe(commonSourceSet)!!
            for (platformTask in platformTasks) {
                platformTask.source(commonSources)
                platformTask.commonSourceSet.from(commonSources)
            }
        }
    }

    private fun getKotlinSourceSetsSafe(project: Project): NamedDomainObjectCollection<out Named> {
        // Access through reflection, because another project's KotlinProjectExtension might be loaded by a different class loader:
        val kotlinExt = project.extensions.getByName("kotlin")

        @Suppress("UNCHECKED_CAST")
        val sourceSets = kotlinExt.javaClass.getMethod("getSourceSets").invoke(kotlinExt) as NamedDomainObjectCollection<out Named>
        return sourceSets
    }

    protected fun getKotlinSourceDirectorySetSafe(from: Any): SourceDirectorySet? {
        val getKotlin = from.javaClass.getMethod("getKotlin")
        return getKotlin(from) as? SourceDirectorySet
    }

    @Deprecated("Migrate to the new Kotlin source sets and use the addCommonSourceSetToPlatformSourceSet(Named, Project) overload")
    protected open fun addCommonSourceSetToPlatformSourceSet(sourceSet: SourceSet, platformProject: Project) = Unit

    @Deprecated("Retained for older Kotlin/Native MPP plugin binary compatibility")
    protected val SourceSet.kotlin: SourceDirectorySet?
        get() {
            // Access through reflection, because another project's KotlinSourceSet might be loaded
            // by a different class loader:
            val convention = (getConvention("kotlin") ?: getConvention("kotlin2js")) ?: return null
            val kotlinSourceSetIface = convention.javaClass.interfaces.find { it.name == KotlinSourceSet::class.qualifiedName }
            val getKotlin = kotlinSourceSetIface?.methods?.find { it.name == "getKotlin" } ?: return null
            return getKotlin(convention) as? SourceDirectorySet
        }
}

internal fun <T> Project.whenEvaluated(fn: Project.() -> T) {
    if (state.executed) {
        fn()
        return
    }

    /* Make sure that all afterEvaluate blocks from the AndroidPlugin get scheduled first */
    val isDispatched = AtomicBoolean(false)

    fun isAndroidPluginApplied(): Boolean =
        androidPluginIds.any { pluginManager.hasPlugin(it) }

    if (isAndroidPluginApplied()) {
        if (!isDispatched.getAndSet(true)) {
            afterEvaluate { fn() }
        }
    } else {
        plugins.all {
            if (!isDispatched.get() && isAndroidPluginApplied() && !isDispatched.getAndSet(true)) {
                afterEvaluate { fn() }
            }
        }
    }

    afterEvaluate {
        /* If no Android plugin was loaded, then the action was not dispatched and we can freely execute it now */
        if (!isDispatched.getAndSet(true)) {
            fn()
        }
    }
}

open class KotlinPlatformAndroidPlugin : KotlinPlatformImplementationPluginBase("android") {
    override fun apply(project: Project) {
        project.applyPlugin<KotlinAndroidPluginWrapper>()
        super.apply(project)
    }

    override fun namedSourceSetsContainer(project: Project): NamedDomainObjectContainer<*> =
        (project.extensions.getByName("android") as BaseExtension).sourceSets

    override fun addCommonSourceSetToPlatformSourceSet(commonSourceSet: Named, platformProject: Project) {
        val androidExtension = platformProject.extensions.getByName("android") as BaseExtension
        val androidSourceSet = androidExtension.sourceSets.findByName(commonSourceSet.name) ?: return
        val kotlinSourceSet = androidSourceSet.getConvention(KOTLIN_DSL_NAME) as? KotlinSourceSet
            ?: return
        kotlinSourceSet.kotlin.source(getKotlinSourceDirectorySetSafe(commonSourceSet)!!)
    }
}

open class KotlinPlatformJvmPlugin : KotlinPlatformImplementationPluginBase("jvm") {
    override fun apply(project: Project) {
        project.applyPlugin<KotlinPluginWrapper>()
        super.apply(project)
    }
}

open class KotlinPlatformJsPlugin : KotlinPlatformImplementationPluginBase("js") {
    override fun apply(project: Project) {
        project.applyPlugin<Kotlin2JsPluginWrapper>()
        super.apply(project)
    }
}

internal val KOTLIN_12X_MPP_DEPRECATION_WARNING = "\n" + """
    The 'org.jetbrains.kotlin.platform.*' plugins are deprecated and will no longer be available in Kotlin 1.4.
    Please migrate the project to the 'org.jetbrains.kotlin.multiplatform' plugin.
    See: https://kotlinlang.org/docs/reference/building-mpp-with-gradle.html
    """.trimIndent()

private const val KOTLIN_12X_MPP_DEPRECATION_SUPPRESS_FLAG = "kotlin.internal.mpp12x.deprecation.suppress"

private fun warnAboutKotlin12xMppDeprecation(project: Project) {
    if (project.findProperty(KOTLIN_12X_MPP_DEPRECATION_SUPPRESS_FLAG) != "true") {
        SingleWarningPerBuild.show(project, KOTLIN_12X_MPP_DEPRECATION_WARNING)
    }
}
