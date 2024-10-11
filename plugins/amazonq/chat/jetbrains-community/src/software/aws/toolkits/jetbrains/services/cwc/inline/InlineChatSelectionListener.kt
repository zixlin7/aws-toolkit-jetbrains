// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.cwc.inline

import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import java.awt.Point
import javax.swing.SwingUtilities

class InlineChatSelectionListener : SelectionListener {
    private var inlineChatEditorHint: InlineChatEditorHint? = null
    override fun selectionChanged(e: SelectionEvent) {
        val editor = e.editor
        val selectionModel = editor.selectionModel

        if (selectionModel.hasSelection()) {
            val selectionEnd = selectionModel.selectionEnd
            val selectionLineEnd = editor.document.getLineEndOffset(editor.document.getLineNumber(selectionEnd))
            val logicalPosition = editor.offsetToLogicalPosition(selectionLineEnd)
            val visualPosition = editor.logicalToVisualPosition(logicalPosition)
            val position = editor.visualPositionToXY(visualPosition)

            val visibleArea = editor.scrollingModel.visibleArea

            val adjustedX = (position.x + 200).coerceAtMost(visibleArea.x + visibleArea.width - 50)
            val adjustedY = (position.y + 20).coerceAtMost(visibleArea.y + visibleArea.height - 50)
            val adjustedPosition = Point(adjustedX, adjustedY)
            val pos = SwingUtilities.convertPoint(
                editor.component,
                adjustedPosition,
                editor.component.rootPane.layeredPane
            )

            inlineChatEditorHint = editor.let { editor.project?.let { project -> InlineChatEditorHint(project, it) } }
            inlineChatEditorHint?.show(pos)
        } else {
            inlineChatEditorHint?.hide()
        }
    }
}
