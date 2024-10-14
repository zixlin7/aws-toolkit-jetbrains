// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.cwc.inline

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import software.aws.toolkits.jetbrains.services.amazonq.apps.AmazonQAppInitContext

class InlineChatFileListener(private val context: AmazonQAppInitContext) : FileEditorManagerListener {
    private var currentEditor: Editor? = context.project.let { FileEditorManager.getInstance(it).selectedTextEditor }
    private var caretListener: ChatCaretListener? = null
    private var selectionListener: InlineChatSelectionListener? = null

    init {
        setupListenersForCurrentEditor()
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val newEditor = (event.newEditor as? TextEditor)?.editor
        if (newEditor != currentEditor) {
            removeListenersFromCurrentEditor()
            currentEditor = newEditor
            setupListenersForCurrentEditor()
        }
    }

    private fun setupListenersForCurrentEditor() {
        currentEditor?.let { editor ->
            caretListener = editor.project?.let {
                ChatCaretListener(it, context).also { listener ->
                    editor.caretModel.addCaretListener(listener)
                }
            }

            selectionListener = InlineChatSelectionListener().also { listener ->
                editor.selectionModel.addSelectionListener(listener)
            }
        }
    }

    private fun removeListenersFromCurrentEditor() {
        currentEditor?.let { editor ->
            caretListener?.let { listener ->
                editor.caretModel.removeCaretListener(listener)
            }
            selectionListener?.let { listener ->
                editor.selectionModel.removeSelectionListener(listener)
            }
        }
        caretListener = null
        selectionListener = null
    }

    fun dispose() {
        removeListenersFromCurrentEditor()
        currentEditor = null
    }
}
