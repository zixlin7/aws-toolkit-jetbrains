// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.amazonq.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.warn

@Service(Service.Level.PROJECT)
class ProjectContextController(private val project: Project, private val cs: CoroutineScope) : Disposable {
    // TODO: Ideally we should inject dependencies via constructor for easier testing, refer to how [TelemetryService] inject publisher and batcher
    private val encoderServer: EncoderServer = EncoderServer(project)
    private val projectContextProvider: ProjectContextProvider = ProjectContextProvider(project, encoderServer, cs)

    val initJob: Job = cs.launch {
        encoderServer.downloadArtifactsAndStartServer()
    }

    fun getProjectContextIndexComplete() = projectContextProvider.isIndexComplete.get()

    fun query(prompt: String): List<RelevantDocument> {
        try {
            return projectContextProvider.query(prompt)
        } catch (e: Exception) {
            logger.warn { "error while querying for project context $e.message" }
            return emptyList()
        }
    }

    fun updateIndex(filePath: String) {
        try {
            return projectContextProvider.updateIndex(filePath)
        } catch (e: Exception) {
            logger.warn { "error while updating index for project context $e.message" }
        }
    }

    override fun dispose() {
        Disposer.dispose(encoderServer)
        Disposer.dispose(projectContextProvider)
    }

    companion object {
        private val logger = getLogger<ProjectContextController>()
        fun getInstance(project: Project) = project.service<ProjectContextController>()
    }
}
