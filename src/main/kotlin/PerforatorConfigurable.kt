package com.perforator.perforator

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.JBUI
import java.awt.Container
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JPasswordField
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PerforatorConfigurable(private val project: Project) : Configurable {

    private var panel: JPanel? = null
    private var baseUrlField: JTextField? = null
    private var profileTypeCombo: JComboBox<String>? = null
    private var serviceNameField: JTextField? = null
    private var timeCombo: JComboBox<String>? = null
    private var cpuThresholdField: JTextField? = null
    private var memoryThresholdField: JTextField? = null
    private var basicAuthUsernameField: JTextField? = null
    private var basicAuthPasswordField: JPasswordField? = null
    private var isModifiedFlag = false

    override fun getDisplayName(): String = "Perforator"

    override fun createComponent(): JComponent {
        val settings = project.service<PerforatorSettingsService>()

        panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(5, 0, 5, 10)
        }

        // Base URL
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel!!.add(JLabel("Pyroscope Base URL:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        baseUrlField = JTextField(settings.baseUrl, 30)
        baseUrlField!!.toolTipText = "e.g., http://localhost:4040"
        panel!!.add(baseUrlField!!, gbc)

        // Basic Auth Username
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Basic Auth Username:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        basicAuthUsernameField = JTextField(settings.basicAuthUsername, 30)
        basicAuthUsernameField!!.toolTipText = "Optional: Username for basic authentication"
        panel!!.add(basicAuthUsernameField!!, gbc)

        // Basic Auth Password
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Basic Auth Password:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        basicAuthPasswordField = JPasswordField(settings.basicAuthPassword, 30)
        basicAuthPasswordField!!.toolTipText = "Optional: Password for basic authentication"
        panel!!.add(basicAuthPasswordField!!, gbc)

        // Profile Type
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Profile Type:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        profileTypeCombo = JComboBox(arrayOf("CPU", "Memory"))
        profileTypeCombo!!.selectedItem = settings.profileType
        panel!!.add(profileTypeCombo!!, gbc)

        // Service Name
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Service Name:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        serviceNameField = JTextField(settings.serviceName, 30)
        serviceNameField!!.toolTipText = "Name of your service as it appears in Pyroscope"
        panel!!.add(serviceNameField!!, gbc)

        // Time Window
        gbc.gridx = 0
        gbc.gridy = 5
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Time Window:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        timeCombo = JComboBox(arrayOf("now-15m", "now-30m", "now-1h", "now-1d", "now-3d", "now-1w"))
        timeCombo!!.selectedItem = settings.timeWindow
        panel!!.add(timeCombo!!, gbc)

        // CPU Hot Threshold
        gbc.gridx = 0
        gbc.gridy = 6
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("CPU Hot Threshold (ms):"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        cpuThresholdField = JTextField(settings.hotThresholdCpu.toString(), 10)
        cpuThresholdField!!.toolTipText = "CPU execution time threshold in milliseconds (1-10000)"
        panel!!.add(cpuThresholdField!!, gbc)

        // Memory Hot Threshold
        gbc.gridx = 0
        gbc.gridy = 7
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Memory Hot Threshold (MB):"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        memoryThresholdField = JTextField(settings.hotThresholdMemory.toString(), 10)
        memoryThresholdField!!.toolTipText = "Memory allocation threshold in megabytes (1-10000)"
        panel!!.add(memoryThresholdField!!, gbc)

        // Help text
        gbc.gridx = 0
        gbc.gridy = 8
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        gbc.insets = JBUI.insets(20, 0, 5, 0)

        val helpText = JLabel(
            "<html>Configure your Pyroscope connection settings.<br/>" +
                    "Basic auth is optional - leave username/password empty if not needed.<br/>" +
                    "CPU profiles show execution time in milliseconds.<br/>" +
                    "Memory profiles show allocation size in megabytes.<br/>" +
                    "Use 'Fetch profiling data' in the Tools menu to load profiling data.</html>"
        )
        helpText.foreground = UIManager.getColor("Label.disabledForeground")
        panel!!.add(helpText, gbc)

        // Listeners to mark modified
        baseUrlField!!.document.addDocumentListener(modificationListener())
        basicAuthUsernameField!!.document.addDocumentListener(modificationListener())
        basicAuthPasswordField!!.document.addDocumentListener(modificationListener())
        profileTypeCombo!!.addActionListener { isModifiedFlag = true }
        serviceNameField!!.document.addDocumentListener(modificationListener())
        timeCombo!!.addActionListener { isModifiedFlag = true }
        cpuThresholdField!!.document.addDocumentListener(modificationListener())
        memoryThresholdField!!.document.addDocumentListener(modificationListener())

        return panel!!
    }

    private fun modificationListener() = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) { isModifiedFlag = true }
        override fun removeUpdate(e: DocumentEvent?) { isModifiedFlag = true }
        override fun changedUpdate(e: DocumentEvent?) { isModifiedFlag = true }
    }

    override fun isModified(): Boolean {
        if (isModifiedFlag) return true

        val settings = project.service<PerforatorSettingsService>()
        val baseUrlChanged = baseUrlField?.text != settings.baseUrl
        val basicAuthUsernameChanged = basicAuthUsernameField?.text != settings.basicAuthUsername
        val basicAuthPasswordChanged = String(basicAuthPasswordField?.password ?: charArrayOf()) != settings.basicAuthPassword
        val profileTypeChanged = profileTypeCombo?.selectedItem != settings.profileType
        val serviceNameChanged = serviceNameField?.text != settings.serviceName
        val timeChanged = timeCombo?.selectedItem != settings.timeWindow
        val cpuThresholdChanged = cpuThresholdField?.text != settings.hotThresholdCpu.toString()
        val memoryThresholdChanged = memoryThresholdField?.text != settings.hotThresholdMemory.toString()

        return baseUrlChanged || basicAuthUsernameChanged || basicAuthPasswordChanged ||
                profileTypeChanged || serviceNameChanged || timeChanged ||
                cpuThresholdChanged || memoryThresholdChanged
    }

    override fun apply() {
        val settings = project.service<PerforatorSettingsService>()

        val baseUrl = baseUrlField?.text?.trim() ?: return
        val basicAuthUsername = basicAuthUsernameField?.text?.trim() ?: ""
        val basicAuthPassword = String(basicAuthPasswordField?.password ?: charArrayOf()).trim()
        val profileType = profileTypeCombo?.selectedItem as? String ?: return
        val serviceName = serviceNameField?.text?.trim() ?: return
        val timeWindow = timeCombo?.selectedItem as? String ?: return

        val cpuThresholdText = cpuThresholdField?.text?.trim() ?: return
        val memoryThresholdText = memoryThresholdField?.text?.trim() ?: return

        val cpuThreshold = cpuThresholdText.toIntOrNull()
        if (cpuThreshold == null || cpuThreshold < 1 || cpuThreshold > 10000) {
            Messages.showErrorDialog(
                project,
                "CPU hot threshold must be a number between 1 and 10000",
                "Invalid Settings"
            )
            return
        }

        val memoryThreshold = memoryThresholdText.toIntOrNull()
        if (memoryThreshold == null || memoryThreshold < 1 || memoryThreshold > 10000) {
            Messages.showErrorDialog(
                project,
                "Memory hot threshold must be a number between 1 and 10000",
                "Invalid Settings"
            )
            return
        }

        val error = settings.validateAndSet(
            baseUrl, profileType, serviceName, timeWindow,
            cpuThreshold, memoryThreshold, basicAuthUsername, basicAuthPassword
        )
        if (error != null) {
            Messages.showErrorDialog(project, error, "Invalid Settings")
            return
        }

        isModifiedFlag = false

        // Update any existing toolbars with new settings
        if (settings.isVisible) {
            PerforatorUiHelpers.updateAllToolbars(project)
        }
    }

    override fun reset() {
        val settings = project.service<PerforatorSettingsService>()

        baseUrlField?.text = settings.baseUrl
        basicAuthUsernameField?.text = settings.basicAuthUsername
        basicAuthPasswordField?.text = settings.basicAuthPassword
        profileTypeCombo?.selectedItem = settings.profileType
        serviceNameField?.text = settings.serviceName
        timeCombo?.selectedItem = settings.timeWindow
        cpuThresholdField?.text = settings.hotThresholdCpu.toString()
        memoryThresholdField?.text = settings.hotThresholdMemory.toString()

        isModifiedFlag = false
    }

    private object PerforatorUiHelpers {
        fun updateAllToolbars(project: Project) {
            val fem = FileEditorManager.getInstance(project)
            for (editor in fem.allEditors) {
                if (editor is TextEditor) {
                    val tb = findToolbarInEditor(editor) ?: continue
                    // Prefer calling a public refresh method on the toolbar.
                    // Try refreshFromSettings(); if it doesn't exist yet, try loadCurrentValues() if public; else ignore.
                    if (!invokeIfExists(tb, "refreshFromSettings")) {
                        invokeIfExists(tb, "loadCurrentValues")
                    }
                }
            }
        }

        private fun findToolbarInEditor(editor: TextEditor): PerforatorToolbar? {
            val editorComponent = editor.editor.component
            var parent: Container? = editorComponent.parent
            repeat(6) {
                when (parent) {
                    is Container -> {
                        for (comp in parent.components) {
                            if (comp is PerforatorToolbar) return comp
                        }
                        parent = parent.parent
                    }
                    else -> return null
                }
            }
            return null
        }

        private fun invokeIfExists(target: Any, methodName: String): Boolean {
            return try {
                val m = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(target)
                    true
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}