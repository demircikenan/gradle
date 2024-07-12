/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.internal.cc.impl

import org.gradle.api.internal.artifacts.ivyservice.ArtifactCachesProvider
import org.gradle.api.internal.artifacts.ivyservice.modulecache.FileStoreAndIndexProvider
import org.gradle.api.internal.file.temp.TemporaryFileProvider
import org.gradle.api.internal.provider.ConfigurationTimeBarrier
import org.gradle.api.internal.tasks.TaskExecutionAccessChecker
import org.gradle.api.internal.tasks.execution.TaskExecutionAccessListener
import org.gradle.execution.ExecutionAccessChecker
import org.gradle.execution.ExecutionAccessListener
import org.gradle.internal.buildoption.InternalOptions
import org.gradle.internal.buildtree.BuildModelParameters
import org.gradle.internal.cc.impl.initialization.ConfigurationCacheStartParameter
import org.gradle.internal.cc.impl.problems.ConfigurationCacheReport
import org.gradle.internal.cc.impl.services.DefaultIsolatedProjectEvaluationListenerProvider
import org.gradle.internal.cc.impl.services.IsolatedActionCodecsFactory
import org.gradle.internal.cc.impl.services.RemoteScriptUpToDateChecker
import org.gradle.internal.concurrent.ExecutorFactory
import org.gradle.internal.event.ListenerManager
import org.gradle.internal.execution.WorkExecutionTracker
import org.gradle.internal.extensions.core.add
import org.gradle.internal.nativeintegration.filesystem.FileSystem
import org.gradle.internal.resource.connector.ResourceConnectorFactory
import org.gradle.internal.resource.connector.ResourceConnectorSpecification
import org.gradle.internal.resource.transfer.ExternalResourceConnector
import org.gradle.internal.service.Provides
import org.gradle.internal.service.ServiceRegistration
import org.gradle.internal.service.ServiceRegistrationProvider
import org.gradle.internal.service.scopes.AbstractGradleModuleServices
import org.gradle.invocation.IsolatedProjectEvaluationListenerProvider
import java.io.File


class ConfigurationCacheServices : AbstractGradleModuleServices() {

    override fun registerBuildSessionServices(registration: ServiceRegistration) {
        registration.run {
            add(DefaultBuildTreeModelControllerServices::class.java)
        }
    }

    override fun registerBuildTreeServices(registration: ServiceRegistration) {
        registration.run {
            add(ConfigurationCacheKey::class.java)
            add(DeprecatedFeaturesListener::class.java)
            add(DefaultBuildModelControllerServices::class.java)
            add(DefaultBuildToolingModelControllerFactory::class.java)
            add(ConfigurationCacheRepository::class.java)
            add(InputTrackingState::class.java)
            add(InstrumentedInputAccessListener::class.java)
            add(InstrumentedExecutionAccessListener::class.java)
            add(IsolatedActionCodecsFactory::class.java)
            addProvider(IgnoredConfigurationInputsProvider)
            addProvider(RemoteScriptUpToDateCheckerProvider)
            addProvider(ExecutionAccessCheckerProvider)
            addProvider(ConfigurationCacheReportProvider)
        }
    }

    override fun registerBuildServices(registration: ServiceRegistration) {
        registration.run {
            add(RelevantProjectsRegistry::class.java)
            addProvider(TaskExecutionAccessCheckerProvider)
            add(DefaultConfigurationCacheHost::class.java)
            add(DefaultConfigurationCacheIO::class.java)
            add<IsolatedProjectEvaluationListenerProvider, DefaultIsolatedProjectEvaluationListenerProvider>()
        }
    }

    private
    object RemoteScriptUpToDateCheckerProvider : ServiceRegistrationProvider {
        @Provides
        fun createRemoteScriptUpToDateChecker(
            artifactCachesProvider: ArtifactCachesProvider,
            startParameter: ConfigurationCacheStartParameter,
            temporaryFileProvider: TemporaryFileProvider,
            fileStoreAndIndexProvider: FileStoreAndIndexProvider,
            resourceConnectorFactories: List<ResourceConnectorFactory>
        ): RemoteScriptUpToDateChecker =
            artifactCachesProvider.withWritableCache { _, cacheLockingManager ->
                RemoteScriptUpToDateChecker(
                    cacheLockingManager,
                    startParameter,
                    temporaryFileProvider,
                    fileStoreAndIndexProvider.externalResourceFileStore,
                    httpResourceConnectorFrom(resourceConnectorFactories),
                    fileStoreAndIndexProvider.externalResourceIndex
                )
            }

        private
        fun httpResourceConnectorFrom(resourceConnectorFactories: List<ResourceConnectorFactory>): ExternalResourceConnector =
            resourceConnectorFactories
                .single { "https" in it.supportedProtocols }
                .createResourceConnector(object : ResourceConnectorSpecification {})
    }

    private
    object ExecutionAccessCheckerProvider : ServiceRegistrationProvider {
        @Provides
        fun createExecutionAccessChecker(
            listenerManager: ListenerManager,
            modelParameters: BuildModelParameters,
            configurationTimeBarrier: ConfigurationTimeBarrier
        ): ExecutionAccessChecker = when {
            modelParameters.isConfigurationCache -> {
                val broadcaster = listenerManager.getBroadcaster(ExecutionAccessListener::class.java)
                ConfigurationTimeBarrierBasedExecutionAccessChecker(configurationTimeBarrier, broadcaster)
            }

            else -> DefaultExecutionAccessChecker()
        }
    }

    private
    object TaskExecutionAccessCheckerProvider : ServiceRegistrationProvider {
        @Provides
        fun createTaskExecutionAccessChecker(
            /** In non-CC builds, [BuildTreeConfigurationCache] is not registered; accepting a list here is a way to ignore its absence. */
            configurationCache: List<BuildTreeConfigurationCache>,
            configurationTimeBarrier: ConfigurationTimeBarrier,
            modelParameters: BuildModelParameters,
            /** In non-CC builds, [ConfigurationCacheStartParameter] is not registered; accepting a list here is a way to ignore its absence. */
            configurationCacheStartParameter: List<ConfigurationCacheStartParameter>,
            listenerManager: ListenerManager,
            workExecutionTracker: WorkExecutionTracker,
        ): TaskExecutionAccessChecker {
            val broadcast = listenerManager.getBroadcaster(TaskExecutionAccessListener::class.java)
            val workGraphLoadingState = workGraphLoadingStateFrom(configurationCache)
            return when {
                !modelParameters.isConfigurationCache -> TaskExecutionAccessCheckers.TaskStateBased(workGraphLoadingState, broadcast, workExecutionTracker)
                configurationCacheStartParameter.single().taskExecutionAccessPreStable -> TaskExecutionAccessCheckers.TaskStateBased(
                    workGraphLoadingState,
                    broadcast,
                    workExecutionTracker
                )

                else -> TaskExecutionAccessCheckers.ConfigurationTimeBarrierBased(configurationTimeBarrier, workGraphLoadingState, broadcast, workExecutionTracker)
            }
        }

        private
        fun workGraphLoadingStateFrom(maybeConfigurationCache: List<BuildTreeConfigurationCache>): WorkGraphLoadingState {
            if (maybeConfigurationCache.isEmpty()) {
                return WorkGraphLoadingState { false }
            }

            val configurationCache = maybeConfigurationCache.single()
            return WorkGraphLoadingState { configurationCache.isLoaded }
        }
    }

    private
    object IgnoredConfigurationInputsProvider : ServiceRegistrationProvider {
        @Provides
        fun createIgnoredConfigurationInputs(
            configurationCacheStartParameter: ConfigurationCacheStartParameter,
            fileSystem: FileSystem
        ): IgnoredConfigurationInputs =
            if (hasIgnoredPaths(configurationCacheStartParameter))
                DefaultIgnoredConfigurationInputs(configurationCacheStartParameter, fileSystem)
            else object : IgnoredConfigurationInputs {
                override fun isFileSystemCheckIgnoredFor(file: File): Boolean = false
            }

        private
        fun hasIgnoredPaths(configurationCacheStartParameter: ConfigurationCacheStartParameter): Boolean =
            !configurationCacheStartParameter.ignoredFileSystemCheckInputs.isNullOrEmpty()
    }

    private
    object ConfigurationCacheReportProvider : ServiceRegistrationProvider {
        @Provides
        fun createConfigurationCacheReport(
            executorFactory: ExecutorFactory,
            temporaryFileProvider: TemporaryFileProvider,
            internalOptions: InternalOptions
        ): ConfigurationCacheReport {
            return ConfigurationCacheReport(executorFactory, temporaryFileProvider, internalOptions)
        }
    }
}
