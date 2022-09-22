// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.spy
import org.mockito.kotlin.stub
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import software.amazon.awssdk.services.codewhisperer.CodeWhispererClient
import software.amazon.awssdk.services.codewhisperer.model.ListRecommendationsRequest
import software.amazon.awssdk.services.codewhisperer.paginators.ListRecommendationsIterable
import software.aws.toolkits.jetbrains.core.MockClientManagerRule
import software.aws.toolkits.jetbrains.services.codewhisperer.CodeWhispererTestUtil.codeWhispererRecommendationActionId
import software.aws.toolkits.jetbrains.services.codewhisperer.CodeWhispererTestUtil.pythonFileName
import software.aws.toolkits.jetbrains.services.codewhisperer.CodeWhispererTestUtil.pythonResponse
import software.aws.toolkits.jetbrains.services.codewhisperer.CodeWhispererTestUtil.pythonTestLeftContext
import software.aws.toolkits.jetbrains.services.codewhisperer.CodeWhispererTestUtil.testValidAccessToken
import software.aws.toolkits.jetbrains.services.codewhisperer.credentials.CodeWhispererClientManager
import software.aws.toolkits.jetbrains.services.codewhisperer.editor.CodeWhispererEditorManager
import software.aws.toolkits.jetbrains.services.codewhisperer.explorer.CodeWhispererExploreActionState
import software.aws.toolkits.jetbrains.services.codewhisperer.explorer.CodeWhispererExploreStateType
import software.aws.toolkits.jetbrains.services.codewhisperer.explorer.CodeWhispererExplorerActionManager
import software.aws.toolkits.jetbrains.services.codewhisperer.popup.CodeWhispererPopupManager
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererInvocationStatus
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererRecommendationManager
import software.aws.toolkits.jetbrains.services.codewhisperer.service.CodeWhispererService
import software.aws.toolkits.jetbrains.services.codewhisperer.settings.CodeWhispererConfiguration
import software.aws.toolkits.jetbrains.services.codewhisperer.settings.CodeWhispererConfigurationType
import software.aws.toolkits.jetbrains.services.codewhisperer.settings.CodeWhispererSettings
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererColorUtil.POPUP_DIM_HEX
import software.aws.toolkits.jetbrains.utils.rules.PythonCodeInsightTestFixtureRule
import software.aws.toolkits.resources.message

open class CodeWhispererTestBase {
    @Rule
    @JvmField
    var projectRule = PythonCodeInsightTestFixtureRule()

    @Rule
    @JvmField
    val mockClientManagerRule = MockClientManagerRule()

    @Rule
    @JvmField
    val disposableRule = DisposableRule()

    protected lateinit var mockClient: CodeWhispererClient

    protected lateinit var popupManagerSpy: CodeWhispererPopupManager
    protected lateinit var clientManagerSpy: CodeWhispererClientManager
    protected lateinit var invocationStatusSpy: CodeWhispererInvocationStatus
    internal lateinit var stateManager: CodeWhispererExplorerActionManager
    protected lateinit var recommendationManager: CodeWhispererRecommendationManager
    protected lateinit var codewhispererService: CodeWhispererService
    protected lateinit var editorManager: CodeWhispererEditorManager
    protected lateinit var settingsManager: CodeWhispererSettings
    private lateinit var originalExplorerActionState: CodeWhispererExploreActionState
    private lateinit var originalSettings: CodeWhispererConfiguration

    @Before
    open fun setUp() {
        mockClient = mockClientManagerRule.create()
        val requestCaptor = argumentCaptor<ListRecommendationsRequest>()
        mockClient.stub {
            on {
                mockClient.listRecommendationsPaginator(requestCaptor.capture())
            } doAnswer {
                ListRecommendationsIterable(mockClient, requestCaptor.lastValue)
            }
        }
        mockClient.stub {
            on {
                mockClient.listRecommendations(any<ListRecommendationsRequest>())
            } doAnswer {
                pythonResponse
            }
        }

        clientManagerSpy = spy(CodeWhispererClientManager.getInstance())
        doNothing().`when`(clientManagerSpy).dispose()
        clientManagerSpy.stub {
            onGeneric {
                getClient()
            } doAnswer {
                mockClient
            }
        }
        ApplicationManager.getApplication().replaceService(CodeWhispererClientManager::class.java, clientManagerSpy, disposableRule.disposable)

        popupManagerSpy = spy(CodeWhispererPopupManager.getInstance())
        popupManagerSpy.reset()
        doNothing().`when`(popupManagerSpy).showPopup(any(), any(), any(), any(), any(), any(), any(), any())
        ApplicationManager.getApplication().replaceService(CodeWhispererPopupManager::class.java, popupManagerSpy, disposableRule.disposable)

        invocationStatusSpy = spy(CodeWhispererInvocationStatus.getInstance())
        invocationStatusSpy.stub {
            on {
                hasEnoughDelayToShowCodeWhisperer()
            } doAnswer {
                true
            }
        }
        ApplicationManager.getApplication().replaceService(CodeWhispererInvocationStatus::class.java, invocationStatusSpy, disposableRule.disposable)

        stateManager = CodeWhispererExplorerActionManager.getInstance()
        recommendationManager = CodeWhispererRecommendationManager.getInstance()
        codewhispererService = CodeWhispererService.getInstance()
        editorManager = CodeWhispererEditorManager.getInstance()
        settingsManager = CodeWhispererSettings.getInstance()

        setFileContext(pythonFileName, pythonTestLeftContext, "")

        originalExplorerActionState = stateManager.state
        originalSettings = settingsManager.state
        stateManager.loadState(
            CodeWhispererExploreActionState().apply {
                CodeWhispererExploreStateType.values().forEach {
                    value[it] = true
                }
                token = testValidAccessToken
            }
        )
        settingsManager.loadState(
            CodeWhispererConfiguration().apply {
                value[CodeWhispererConfigurationType.IsIncludeCodeWithReference] = true
            }
        )
        stateManager.setAutoEnabled(false)
    }

    @After
    open fun tearDown() {
        stateManager.loadState(originalExplorerActionState)
        settingsManager.loadState(originalSettings)
        popupManagerSpy.reset()
    }

    fun withCodeWhispererServiceInvokedAndWait(runnable: () -> Unit) {
        val popupCaptor = argumentCaptor<JBPopup>()
        invokeCodeWhispererService()
        verify(popupManagerSpy, timeout(5000).atLeastOnce())
            .showPopup(any(), any(), any(), any(), any(), popupCaptor.capture(), any(), any())
        val popup = popupCaptor.lastValue

        try {
            runnable()
        } finally {
            runInEdtAndWait {
                CodeWhispererPopupManager.getInstance().closePopup(popup)
            }
        }
    }

    fun invokeCodeWhispererService() {
        runInEdtAndWait {
            projectRule.fixture.performEditorAction(codeWhispererRecommendationActionId)
        }
        while (CodeWhispererInvocationStatus.getInstance().hasExistingInvocation()) {
            Thread.sleep(10)
        }
    }

    fun checkRecommendationInfoLabelText(selected: Int, total: Int) {
        // should show "[selected] of [total]"
        val actual = popupManagerSpy.popupComponents.recommendationInfoLabel.text
        val expected = message("codewhisperer.popup.recommendation_info", selected, total, POPUP_DIM_HEX)
        assertThat(actual).isEqualTo(expected)
    }

    fun setFileContext(filename: String, leftContext: String, rightContext: String) {
        projectRule.fixture.configureByText(filename, leftContext + rightContext)
        runInEdtAndWait {
            projectRule.fixture.editor.caretModel.primaryCaret.moveToOffset(leftContext.length)
        }
    }
}
