// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.plugin

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.InstalledPluginsState
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.PluginDownloader
import com.intellij.openapi.updateSettings.impl.UpdateChecker
import com.intellij.util.Alarm
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.AwsPlugin
import software.aws.toolkits.jetbrains.AwsToolkit
import software.aws.toolkits.jetbrains.settings.AwsSettings
import software.aws.toolkits.jetbrains.settings.AwsSettingsSharedConfigurable
import software.aws.toolkits.jetbrains.utils.notifyInfo
import software.aws.toolkits.resources.AwsCoreBundle
import software.aws.toolkits.telemetry.Component
import software.aws.toolkits.telemetry.Result
import software.aws.toolkits.telemetry.ToolkitTelemetry

@Service
class PluginUpdateManager : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    fun scheduleAutoUpdate() {
        if (alarm.isDisposed) return
        scheduleUpdateTask()

        val enabled = AwsSettings.getInstance().isAutoUpdateEnabled
        LOG.debug { "AWS plugins checking for new updates. Auto update enabled: $enabled" }

        if (!enabled) return

        runInEdt {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                null,
                AwsCoreBundle.message("aws.settings.auto_update.progress.message")
            ) {
                override fun run(indicator: ProgressIndicator) {
                    checkForUpdates(indicator, AwsPlugin.CORE)
                    checkForUpdates(indicator, AwsPlugin.TOOLKIT)
                    checkForUpdates(indicator, AwsPlugin.Q)
                }
            })
        }
    }

    private fun scheduleUpdateTask() {
        alarm.addRequest({ scheduleAutoUpdate() }, UPDATE_CHECK_INTERVAL_IN_MS)
    }

    @RequiresBackgroundThread
    fun checkForUpdates(progressIndicator: ProgressIndicator, plugin: AwsPlugin) {
        val pluginInfo = AwsToolkit.PLUGINS_INFO[plugin] ?: return
        val pluginDescriptor = pluginInfo.descriptor as? IdeaPluginDescriptor ?: return
        val pluginName = pluginInfo.name
        // Note: This will need to handle exceptions and ensure thread-safety
        try {
            // If plugin is not installed, do not perform auto-update
            if (!PluginManagerCore.isPluginInstalled(pluginDescriptor.pluginId)) {
                LOG.debug { "$pluginName is not detected as installed, not performing auto-update" }
                return
            }

            if (!updatePlugin(pluginDescriptor, progressIndicator)) return

            // TODO: distinguish telemetry
            ToolkitTelemetry.showAction(
                project = null,
                success = true,
                id = SOURCE_AUTO_UPDATE_FINISH_NOTIFY,
                source = SOURCE_AUTO_UPDATE_FINISH_NOTIFY,
                component = Component.Filesystem
            )
        } catch (e: Exception) {
            LOG.debug(e) { "Unable to update $pluginName" }
            // TODO: distinguish telemetry
            ToolkitTelemetry.showAction(
                project = null,
                success = false,
                id = SOURCE_AUTO_UPDATE_FINISH_NOTIFY,
                source = SOURCE_AUTO_UPDATE_FINISH_NOTIFY,
                component = Component.Filesystem,
                reason = e.message
            )
            return
        } catch (e: Error) {
            // Handle cases like NoSuchMethodError when the API is not available in certain versions
            LOG.debug(e) { "Unable to update $pluginName" }
            // TODO: distinguish telemetry
            ToolkitTelemetry.showAction(
                project = null,
                success = false,
                id = SOURCE_AUTO_UPDATE_FINISH_NOTIFY,
                source = SOURCE_AUTO_UPDATE_FINISH_NOTIFY,
                component = Component.Filesystem,
                reason = e.message
            )
            return
        }
        if (!AwsSettings.getInstance().isAutoUpdateNotificationEnabled) return
        if (plugin == AwsPlugin.CORE) return
        notifyInfo(
            title = AwsCoreBundle.message("aws.notification.auto_update.title", pluginName),
            content = AwsCoreBundle.message("aws.settings.auto_update.notification.message"),
            project = null,
            notificationActions = listOf(
                NotificationAction.createSimpleExpiring(AwsCoreBundle.message("aws.settings.auto_update.notification.yes")) {
                    // TODO: distinguish telemetry
                    ToolkitTelemetry.invokeAction(
                        project = null,
                        result = Result.Succeeded,
                        id = "autoUpdateActionRestart",
                        source = SOURCE_AUTO_UPDATE_FINISH_NOTIFY,
                        component = Component.Filesystem,
                        action = "restart"
                    )
                    ApplicationManager.getApplication().restart()
                },
                NotificationAction.createSimpleExpiring(AwsCoreBundle.message("aws.settings.auto_update.notification.no")) {
                    // TODO: distinguish telemetry
                    ToolkitTelemetry.invokeAction(
                        project = null,
                        result = Result.Succeeded,
                        id = "autoUpdateActionNotNow",
                        source = SOURCE_AUTO_UPDATE_FINISH_NOTIFY,
                        component = Component.Filesystem,
                        action = "notNow"
                    )
                },
                NotificationAction.createSimple(AwsCoreBundle.message("aws.notification.auto_update.settings.title")) {
                    // TODO: distinguish telemetry
                    ToolkitTelemetry.invokeAction(
                        project = null,
                        result = Result.Succeeded,
                        id = ID_ACTION_AUTO_UPDATE_SETTINGS,
                        source = SOURCE_AUTO_UPDATE_FINISH_NOTIFY,
                        component = Component.Filesystem,
                        action = "showSettingsDialog"
                    )
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, AwsSettingsSharedConfigurable::class.java)
                }
            )
        )
    }

    fun notifyAutoUpdateFeature(project: Project) {
        notifyInfo(
            title = AwsCoreBundle.message("aws.notification.auto_update.feature_intro.title"),
            project = project,
            notificationActions = listOf(
                NotificationAction.createSimpleExpiring(AwsCoreBundle.message("aws.notification.auto_update.feature_intro.ok")) {},
                NotificationAction.createSimple(AwsCoreBundle.message("aws.notification.auto_update.settings.title")) {
                    ToolkitTelemetry.invokeAction(
                        project = null,
                        result = Result.Succeeded,
                        id = ID_ACTION_AUTO_UPDATE_SETTINGS,
                        source = SOURCE_AUTO_UPDATE_FEATURE_INTRO_NOTIFY,
                        component = Component.Filesystem,
                        action = "showSettingsDialog"
                    )
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AwsSettingsSharedConfigurable::class.java)
                }
            )
        )
    }

    override fun dispose() {}

    companion object {
        fun getInstance(): PluginUpdateManager = service()
        private val LOG = getLogger<PluginUpdateManager>()
        private const val UPDATE_CHECK_INTERVAL_IN_MS = 4 * 60 * 60 * 1000 // 4 hours
        private const val SOURCE_AUTO_UPDATE_FINISH_NOTIFY = "autoUpdateFinishNotification"
        const val SOURCE_AUTO_UPDATE_FEATURE_INTRO_NOTIFY = "autoUpdateFeatureIntroNotification"
        const val ID_ACTION_AUTO_UPDATE_SETTINGS = "autoUpdateActionSettings"

        fun getUpdate(pluginDescriptor: IdeaPluginDescriptor): PluginDownloader? =
            getUpdateInfo().firstOrNull {
                it.id == pluginDescriptor.pluginId &&
                    PluginDownloader.compareVersionsSkipBrokenAndIncompatible(it.pluginVersion, pluginDescriptor) > 0
            }

        // TODO: Optimize this to only search the result for AWS plugins
        fun getUpdateInfo(): Collection<PluginDownloader> = UpdateChecker.getPluginUpdates() ?: emptyList()

        fun updatePlugin(pluginDescriptor: IdeaPluginDescriptor, progressIndicator: ProgressIndicator): Boolean {
            val pluginName = pluginDescriptor.name

            // wasUpdatedWithRestart means that, it was an update and it needs to restart to apply
            if (InstalledPluginsState.getInstance().wasUpdatedWithRestart(pluginDescriptor.pluginId)) {
                LOG.info { "$pluginName was recently updated and needed restart, not performing auto-update again" }
                return false
            }

            if (pluginDescriptor.version.contains("SNAPSHOT", ignoreCase = true)) {
                LOG.info { "$pluginName is a SNAPSHOT version, not performing auto-update" }
                return false
            }
            if (!pluginDescriptor.isEnabled) {
                LOG.info { "$pluginName is disabled, not performing auto-update" }
                return false
            }
            LOG.debug { "Current version: ${pluginDescriptor.version}" }
            val latestPluginDownloader = getUpdate(pluginDescriptor)
            if (latestPluginDownloader == null) {
                LOG.info { "$pluginName no newer version found, not performing auto-update" }
                return false
            } else {
                LOG.info { "$pluginName found newer version: ${latestPluginDownloader.pluginVersion}" }
            }

            if (!latestPluginDownloader.prepareToInstall(progressIndicator)) return false
            latestPluginDownloader.install()

            return true
        }
    }
}
