// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.cwc.inline.listeners

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import software.aws.toolkits.jetbrains.services.cwc.inline.InlineChatController

class InlineChatFileListener(project: Project, private val controller: InlineChatController) : FileEditorManagerListener {
    private var currentEditor: Editor? = null
    private var selectionListener: InlineChatSelectionListener? = null

    init {
        val editor = project.let { FileEditorManager.getInstance(it).selectedTextEditor }
        if (editor != null) {
            setupListenersForEditor(editor)
            currentEditor = editor
        }
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val newEditor = (event.newEditor as? TextEditor)?.editor
        if (newEditor != null && newEditor != currentEditor) {
            currentEditor?.let { removeListenersFromCurrentEditor(it) }
            setupListenersForEditor(newEditor)
            currentEditor = newEditor
            controller.disposePopup(true)
        }
    }

    private fun setupListenersForEditor(editor: Editor) {
        selectionListener = InlineChatSelectionListener().also { listener ->
            editor.selectionModel.addSelectionListener(listener)
        }
    }

    private fun removeListenersFromCurrentEditor(editor: Editor) {
        selectionListener?.let { listener ->
            editor.selectionModel.removeSelectionListener(listener)
            listener.dispose()
        }
        selectionListener = null
    }

    fun dispose() {
        currentEditor?.let { removeListenersFromCurrentEditor(it) }
        currentEditor = null
    }
}
