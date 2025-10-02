package com.perforator.perforator

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.JBUI
import java.awt.Container
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Settings dialog for Perforator plugin
 * Provides configuration UI for Pyroscope connection and profiling parameters
 * Accessible via Settings/Preferences -> Tools -> Perforator
 */
class PerforatorConfigurable(private val project: Project) : Configurable {

    // UI components
    private var panel: JPanel? = null

    // Pyroscope connection settings
    private var baseUrlField: JTextField? = null
    private var basicAuthUsernameField: JTextField? = null
    private var basicAuthPasswordField: JPasswordField? = null
    private var serviceNameField: JTextField? = null

    // Profile configuration settings
    private var profileTypeCombo: JComboBox<String>? = null
    private var timeCombo: JComboBox<String>? = null
    private var cpuThresholdField: JTextField? = null
    private var memoryThresholdField: JTextField? = null

    // Track if settings have been modified
    private var isModifiedFlag = false

    /**
     * Returns the display name shown in Settings dialog
     */
    override fun getDisplayName(): String = "Perforator"

    /**
     * Creates and returns the settings UI panel
     */
    override fun createComponent(): JComponent {
        val settings = project.service<PerforatorSettingsService>()

        panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(5, 0, 5, 10)
        }

        var currentRow = 0

        // Pyroscope Base URL field
        gbc.gridx = 0
        gbc.gridy = currentRow
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel!!.add(JLabel("Pyroscope Base URL:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        baseUrlField = JTextField(settings.baseUrl, 30).apply {
            toolTipText = "e.g., http://localhost:4040"
        }
        panel!!.add(baseUrlField!!, gbc)
        currentRow++

        // Service Name field
        gbc.gridx = 0
        gbc.gridy = currentRow
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Service Name:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        serviceNameField = JTextField(settings.serviceName, 30).apply {
            toolTipText = "Name of your service as it appears in Pyroscope"
        }
        panel!!.add(serviceNameField!!, gbc)
        currentRow++

        // Basic Auth Username field
        gbc.gridx = 0
        gbc.gridy = currentRow
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Basic Auth Username:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        basicAuthUsernameField = JTextField(settings.basicAuthUsername, 30).apply {
            toolTipText = "Optional: Username for basic authentication"
        }
        panel!!.add(basicAuthUsernameField!!, gbc)
        currentRow++

        // Basic Auth Password field
        gbc.gridx = 0
        gbc.gridy = currentRow
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Basic Auth Password:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        basicAuthPasswordField = JPasswordField(settings.basicAuthPassword, 30).apply {
            toolTipText = "Optional: Password for basic authentication"
        }
        panel!!.add(basicAuthPasswordField!!, gbc)
        currentRow++

        // Visual separator between sections
        gbc.gridx = 0
        gbc.gridy = currentRow
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(10, 0, 10, 0)
        panel!!.add(JSeparator(SwingConstants.HORIZONTAL), gbc)
        gbc.gridwidth = 1
        gbc.insets = JBUI.insets(5, 0, 5, 10)
        currentRow++

        // Add common profiling settings
        addCommonSettings(gbc, currentRow, settings)

        // Register listeners to detect modifications
        baseUrlField!!.document.addDocumentListener(modificationListener)
        serviceNameField!!.document.addDocumentListener(modificationListener)
        basicAuthUsernameField!!.document.addDocumentListener(modificationListener)
        basicAuthPasswordField!!.document.addDocumentListener(modificationListener)

        return panel!!
    }

    /**
     * Adds common profiling configuration fields to the settings panel
     */
    private fun addCommonSettings(gbc: GridBagConstraints, startRow: Int, settings: PerforatorSettingsService) {
        var currentRow = startRow

        // Profile Type selector
        gbc.gridx = 0
        gbc.gridy = currentRow
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        gbc.weighty = 0.0
        panel!!.add(JLabel("Profile Type:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        profileTypeCombo = JComboBox(arrayOf("CPU", "Memory")).apply {
            selectedItem = settings.profileType
            toolTipText = "Type of profiling data to collect"
        }
        panel!!.add(profileTypeCombo!!, gbc)
        currentRow++

        // Time Window selector
        gbc.gridx = 0
        gbc.gridy = currentRow
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Time Window:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        timeCombo = JComboBox(arrayOf("now-15m", "now-30m", "now-1h", "now-1d", "now-3d", "now-1w")).apply {
            selectedItem = settings.timeWindow
            toolTipText = "Time range for profiling data"
        }
        panel!!.add(timeCombo!!, gbc)
        currentRow++

        // CPU Hot Threshold field
        gbc.gridx = 0
        gbc.gridy = currentRow
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("CPU Hot Threshold (ms):"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        cpuThresholdField = JTextField(settings.hotThresholdCpu.toString(), 30).apply {
            toolTipText = "Minimum CPU time in milliseconds to highlight as hot"
        }
        panel!!.add(cpuThresholdField!!, gbc)
        currentRow++

        // Memory Hot Threshold field
        gbc.gridx = 0
        gbc.gridy = currentRow
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel!!.add(JLabel("Memory Hot Threshold (MB):"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        memoryThresholdField = JTextField(settings.hotThresholdMemory.toString(), 30).apply {
            toolTipText = "Minimum memory allocation in MB to highlight as hot"
        }
        panel!!.add(memoryThresholdField!!, gbc)
        currentRow++

        // Spacer panel to push content to top
        gbc.gridx = 0
        gbc.gridy = currentRow
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.BOTH
        gbc.weighty = 1.0
        panel!!.add(JPanel(), gbc)

        // Register modification listeners
        profileTypeCombo!!.addActionListener { isModifiedFlag = true }
        timeCombo!!.addActionListener { isModifiedFlag = true }
        cpuThresholdField!!.document.addDocumentListener(modificationListener)
        memoryThresholdField!!.document.addDocumentListener(modificationListener)
    }

    /**
     * Document listener to track when any field is modified
     */
    private val modificationListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) { isModifiedFlag = true }
        override fun removeUpdate(e: DocumentEvent?) { isModifiedFlag = true }
        override fun changedUpdate(e: DocumentEvent?) { isModifiedFlag = true }
    }

    /**
     * Checks if settings have been modified
     */
    override fun isModified(): Boolean = isModifiedFlag

    /**
     * Applies settings changes and validates input
     * Called when user clicks "Apply" or "OK" in settings dialog
     */
    override fun apply() {
        val settings = project.service<PerforatorSettingsService>()

        // Extract values from UI fields
        val baseUrl = baseUrlField!!.text.trim()
        val basicAuthUsername = basicAuthUsernameField!!.text.trim()
        val basicAuthPassword = String(basicAuthPasswordField!!.password).trim()
        val serviceName = serviceNameField!!.text.trim()
        val profileType = profileTypeCombo!!.selectedItem as String
        val timeWindow = timeCombo!!.selectedItem as String

        // Parse and validate CPU threshold
        val cpuThresholdText = cpuThresholdField!!.text.trim().replace(",", ".")
        val cpuThreshold = cpuThresholdText.toDoubleOrNull()
        if (cpuThreshold == null || cpuThreshold < 0.001 || cpuThreshold > 10000.0) {
            Messages.showErrorDialog(
                project,
                "CPU hot threshold must be a number between 0.001 and 10000",
                "Invalid Settings"
            )
            return
        }

        // Parse and validate Memory threshold
        val memoryThresholdText = memoryThresholdField!!.text.trim().replace(",", ".")
        val memoryThreshold = memoryThresholdText.toDoubleOrNull()
        if (memoryThreshold == null || memoryThreshold < 0.01 || memoryThreshold > 10000.0) {
            Messages.showErrorDialog(
                project,
                "Memory hot threshold must be a number between 0.01 and 10000",
                "Invalid Settings"
            )
            return
        }

        // Attempt to save settings with validation
        val error = settings.validateAndSet(
            baseUrl = baseUrl,
            basicAuthUsername = basicAuthUsername,
            basicAuthPassword = basicAuthPassword,
            profileType = profileType,
            serviceName = serviceName,
            timeWindow = timeWindow,
            hotThresholdCpu = cpuThreshold,
            hotThresholdMemory = memoryThreshold
        )

        // Show error if validation failed
        if (error != null) {
            Messages.showErrorDialog(project, error, "Invalid Settings")
            return
        }

        // Successfully saved - reset modified flag
        isModifiedFlag = false

        // Update any visible toolbars with new settings
        if (settings.isVisible) {
            PerforatorUiHelpers.updateAllToolbars(project)
        }
    }

    /**
     * Resets UI fields to current saved settings
     * Called when user clicks "Reset" in settings dialog
     */
    override fun reset() {
        val settings = project.service<PerforatorSettingsService>()

        baseUrlField?.text = settings.baseUrl
        basicAuthUsernameField?.text = settings.basicAuthUsername
        basicAuthPasswordField?.text = settings.basicAuthPassword
        serviceNameField?.text = settings.serviceName
        profileTypeCombo?.selectedItem = settings.profileType
        timeCombo?.selectedItem = settings.timeWindow
        cpuThresholdField?.text = formatDecimalValue(settings.hotThresholdCpu)
        memoryThresholdField?.text = formatDecimalValue(settings.hotThresholdMemory)

        isModifiedFlag = false
    }

    /**
     * Formats decimal values for display in UI
     * Removes unnecessary trailing zeros and decimal points
     */
    private fun formatDecimalValue(value: Double): String {
        return if (value == value.toInt().toDouble()) {
            // Whole number - show without decimals
            value.toInt().toString()
        } else {
            // Decimal number - show with up to 2 decimal places
            String.format("%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    /**
     * Helper object for updating toolbar UI components
     */
    private object PerforatorUiHelpers {

        /**
         * Updates all visible toolbars with current settings
         */
        fun updateAllToolbars(project: Project) {
            val fem = FileEditorManager.getInstance(project)
            for (editor in fem.allEditors) {
                if (editor is TextEditor) {
                    val toolbar = findToolbarInEditor(editor) ?: continue

                    // Try to call refresh method on toolbar
                    if (!invokeIfExists(toolbar, "refreshFromSettings")) {
                        invokeIfExists(toolbar, "loadCurrentValues")
                    }
                }
            }
        }

        /**
         * Finds PerforatorToolbar component in editor hierarchy
         */
        private fun findToolbarInEditor(editor: TextEditor): PerforatorToolbar? {
            val editorComponent = editor.editor.component
            var parent: Container? = editorComponent.parent

            // Search up to 6 levels in component hierarchy
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

        /**
         * Invokes method by name using reflection if it exists
         * @return true if method was found and invoked, false otherwise
         */
        private fun invokeIfExists(target: Any, methodName: String): Boolean {
            return try {
                val method = target.javaClass.methods.firstOrNull {
                    it.name == methodName && it.parameterCount == 0
                }
                if (method != null) {
                    method.isAccessible = true
                    method.invoke(target)
                    true
                } else {
                    false
                }
            } catch (t: Throwable) {
                false
            }
        }
    }
}