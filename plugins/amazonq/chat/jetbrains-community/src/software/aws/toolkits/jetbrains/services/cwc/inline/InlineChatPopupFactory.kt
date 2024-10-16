// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.cwc.inline

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererColorUtil.POPUP_BUTTON_BORDER
import software.aws.toolkits.resources.AmazonQBundle.message
import java.awt.Point


class InlineChatPopupFactory(
    private val editor: Editor,
    private val submitHandler: suspend (String, String, Int, Editor) -> String,
    private val acceptHandler: () -> Unit,
    private val rejectHandler: () -> Unit,
    private val cancelHandler: () -> Unit,
) : Disposable {

    private fun getSelectedText(editor: Editor): String {
        return ReadAction.compute<String, Throwable> {
            val selectionStartOffset = editor.selectionModel.selectionStart
            val selectionEndOffset = editor.selectionModel.selectionEnd
            if (selectionEndOffset > selectionStartOffset) {
                val selectionLineStart = editor.document.getLineStartOffset(editor.document.getLineNumber(selectionStartOffset))
                val selectionLineEnd = editor.document.getLineEndOffset(editor.document.getLineNumber(selectionEndOffset))
                editor.document.getText(TextRange(selectionLineStart, selectionLineEnd))
            } else ""
        }
    }

    private fun getSelectionStartLine(editor: Editor): Int {
        return ReadAction.compute<Int, Throwable> {
            editor.document.getLineNumber(editor.selectionModel.selectionStart)
        }
    }

    fun createPopup(scope: CoroutineScope): JBPopup {
        val popupPanel = InlineChatPopupPanel(this).apply {
            border = IdeBorderFactory.createRoundedBorder(10).apply {
                setColor(POPUP_BUTTON_BORDER)
            }

            val submitListener: () -> Unit = {
                submitButton.isEnabled = false
                textField.isEnabled = false
                val prompt = textField.text
                if (prompt.isNotBlank()) {
                    setLabel(message("amazonqInlineChat.popup.generating"))
                    revalidate()

                    scope.launch {
                        val selectedCode = getSelectedText(editor)
                        val selectedLineStart = getSelectionStartLine(editor)
                        var errorMessage = ""
                        runBlocking {
                            errorMessage = submitHandler(prompt, selectedCode, selectedLineStart, editor)
                        }
                        if (errorMessage.isNotEmpty()) {
                            setErrorMessage(errorMessage)
                            revalidate()
                        } else {
                            val acceptAction = {
                                acceptHandler.invoke()
                            }
                            val rejectAction = {
                                rejectHandler.invoke()
                            }
                            addCodeActionsPanel(acceptAction , rejectAction)
                        }
                    }
                }
            }
            setSubmitClickListener(submitListener)
        }
        val popup = initPopup(popupPanel)
        showPopupInEditor(popup, popupPanel, editor)

        return popup
    }

    private fun showPopupInEditor(popup: JBPopup, popupPanel: InlineChatPopupPanel, editor: Editor) {
        val popupHeight = popupPanel.POPUP_HEIGHT
        val editorComponent = editor.component
        val locationOnScreen = editorComponent.locationOnScreen
        val popupPoint = JBPopupFactory.getInstance().guessBestPopupLocation(editor).point

        val spaceAbove = popupPoint.y - locationOnScreen.y
        val spaceNeeded = popupHeight + 15 // Add a small buffer

        val adjustedPoint = if (spaceAbove >= spaceNeeded) {
            // Position above the caret
            Point(popupPoint.x, popupPoint.y - spaceNeeded)
        } else {
            // Position below the caret
            popupPoint
        }
        popup.show(RelativePoint(adjustedPoint))

        popupPanel.textField.requestFocusInWindow()
        popupPanel.textField.addActionListener { e ->
            val inputText = popupPanel.textField.text.trim()
            if (inputText.isNotEmpty()) {
                popupPanel.submitButton.doClick()
            }
        }
    }

    private fun initPopup(panel: InlineChatPopupPanel): JBPopup {
        val cancelButton = IconButton(message("amazonqInlineChat.popup.cancel"), AllIcons.Actions.Cancel)
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel.textField)
            .setMovable(true)
            .setResizable(true)
            .setTitle(message("amazonqInlineChat.popup.title"))
            .setCancelButton(cancelButton)
            .setCancelCallback {
                cancelHandler.invoke()
                true
            }
            .setShowBorder(true)
            .setCancelOnWindowDeactivation(false)
            .setAlpha(0.2F)
            .setCancelOnClickOutside(false)
            .setCancelOnOtherWindowOpen(false)
            .setFocusable(true)
            .setRequestFocus(true)
            .setLocateWithinScreenBounds(true)
            .createPopup()
        return popup
    }

    override fun dispose() {
        cancelHandler.invoke()
    }
}
