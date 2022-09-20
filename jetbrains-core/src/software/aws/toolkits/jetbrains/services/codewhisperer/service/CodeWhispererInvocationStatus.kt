// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class CodeWhispererInvocationStatus {
    private val isInvokingCodeWhisperer: AtomicBoolean = AtomicBoolean(false)
    private var timeAtLastInvocationComplete: Instant? = null
    private var timeAtLastDocumentChanged: Instant? = null
    private var isPopupActive: Boolean = false
    private var timeAtLastInvocationStart: Instant? = null

    fun checkExistingInvocationAndSet(): Boolean =
        if (isInvokingCodeWhisperer.getAndSet(true)) {
            LOG.debug { "Have existing CodeWhisperer invocation" }
            true
        } else {
            ApplicationManager.getApplication().messageBus.syncPublisher(CODEWHISPERER_INVOCATION_STATE_CHANGED).invocationStateChanged(true)
            LOG.debug { "Starting CodeWhisperer invocation" }
            false
        }

    fun hasExistingInvocation(): Boolean = isInvokingCodeWhisperer.get()

    fun finishInvocation() {
        if (isInvokingCodeWhisperer.compareAndSet(true, false)) {
            ApplicationManager.getApplication().messageBus.syncPublisher(CODEWHISPERER_INVOCATION_STATE_CHANGED).invocationStateChanged(false)
            LOG.debug { "Ending CodeWhisperer invocation" }
        }
    }

    fun setInvocationComplete() {
        timeAtLastInvocationComplete = Instant.now()
    }

    fun documentChanged() {
        timeAtLastDocumentChanged = Instant.now()
    }

    fun hasEnoughDelayToShowCodeWhisperer(): Boolean {
        val timeCanShowCodeWhisperer = timeAtLastDocumentChanged?.plusMillis(CodeWhispererConstants.POPUP_DELAY) ?: return true
        return timeCanShowCodeWhisperer.isBefore(Instant.now())
    }

    fun isPopupActive(): Boolean = isPopupActive

    fun setPopupActive(value: Boolean) {
        isPopupActive = value
    }

    fun setInvocationStart() {
        timeAtLastInvocationStart = Instant.now()
    }

    fun hasEnoughDelayToInvokeCodeWhisperer(): Boolean {
        // TODO: this is currently set to 2050 to account for the server side 0.5 TPS and and extra 50 ms buffer to
        // avoid ThrottlingException as much as possible.
        val timeCanShowCodeWhisperer = timeAtLastInvocationStart?.plusMillis(2050) ?: return true
        return timeCanShowCodeWhisperer.isBefore(Instant.now())
    }

    companion object {
        private val LOG = getLogger<CodeWhispererInvocationStatus>()
        fun getInstance(): CodeWhispererInvocationStatus = service()
        val CODEWHISPERER_INVOCATION_STATE_CHANGED: Topic<CodeWhispererInvocationStateChangeListener> = Topic.create(
            "CodeWhisperer popup state changed",
            CodeWhispererInvocationStateChangeListener::class.java
        )
    }
}

interface CodeWhispererInvocationStateChangeListener {
    fun invocationStateChanged(value: Boolean) {}
}
