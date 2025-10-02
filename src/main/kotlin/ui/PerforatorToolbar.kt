package com.perforator.perforator

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * Toolbar component for Perforator plugin
 * Provides UI controls for profile type, time window, and threshold settings
 * Appears at the top of editor windows when profiling data is loaded
 */
class PerforatorToolbar(private val project: Project) : JPanel() {

    // UI Components
    private val profileTypeCombo = JComboBox(arrayOf("CPU", "Memory"))
    private val timeCombo = JComboBox(arrayOf("now-15m", "now-30m", "now-1h", "now-1d", "now-3d", "now-1w"))
    private val thresholdField = JTextField()
    private val thresholdUnitLabel = JLabel()
    private val applyButton = JButton("Apply")
    private val closeButton = JButton("×")

    // State tracking
    private var isUpdating = false

    // Component groups for dynamic visibility control
    private lateinit var pyroscopeComponents: List<JComponent>
    private lateinit var thresholdComponents: List<JComponent>

    init {
        setupUI()
        setupActions()
        registerToolbar()
        loadCurrentValues()
    }

    companion object {
        // Track all active toolbars across editors
        private val allToolbars = mutableSetOf<PerforatorToolbar>()
        private var isListenerRegistered = false

        /**
         * Shows toolbar in all currently open editors
         * Registers listener for new file openings
         */
        fun showInAllEditors(project: Project) {
            val settings = project.service<PerforatorSettingsService>()
            settings.isVisible = true

            // Register listener for new files if not already done
            if (!isListenerRegistered) {
                registerGlobalListener(project)
                isListenerRegistered = true
            }

            ApplicationManager.getApplication().invokeLater {
                val fem = FileEditorManager.getInstance(project)

                // Add toolbar to all currently open editors
                for (editor in fem.allEditors) {
                    if (editor is TextEditor) {
                        addToEditor(project, editor)
                    }
                }
            }
        }

        /**
         * Registers global file opening listener
         * Automatically adds toolbar when new files are opened
         */
        private fun registerGlobalListener(project: Project) {
            val connection = project.messageBus.connect()
            connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                        val settings = project.service<PerforatorSettingsService>()
                        if (settings.isVisible) {
                            ApplicationManager.getApplication().invokeLater {
                                val editor = source.getSelectedEditor(file)
                                if (editor is TextEditor) {
                                    addToEditor(project, editor)
                                }
                            }
                        }
                    }
                }
            )
        }

        /**
         * Adds toolbar to a specific editor
         * Handles layout detection and parent container injection
         */
        internal fun addToEditor(project: Project, editor: TextEditor) {
            SwingUtilities.invokeLater {
                val editorComponent = editor.editor.component

                // Check if toolbar already exists
                var parent = editorComponent.parent
                var attempts = 0
                while (parent != null && attempts < 10) {
                    attempts++
                    if (parent is Container) {
                        for (component in parent.components) {
                            if (component is PerforatorToolbar) {
                                return@invokeLater // Already exists
                            }
                        }
                    }

                    // Try to add toolbar when parent layout is BorderLayout
                    if (parent.layout is BorderLayout) {
                        val toolbar = PerforatorToolbar(project)
                        parent.add(toolbar, BorderLayout.NORTH)
                        parent.revalidate()
                        parent.repaint()
                        return@invokeLater
                    }

                    parent = parent.parent
                }

                // Last resort: wrap editor in new panel
                val wrapper = JPanel(BorderLayout())
                val originalParent = editorComponent.parent as? Container
                originalParent?.let { op ->
                    op.remove(editorComponent)
                    wrapper.add(editorComponent, BorderLayout.CENTER)
                    wrapper.add(PerforatorToolbar(project), BorderLayout.NORTH)
                    op.add(wrapper, BorderLayout.CENTER)
                    op.revalidate()
                    op.repaint()
                }
            }
        }

        /**
         * Hides toolbar from all editors
         */
        fun hideFromAllEditors(project: Project) {
            val settings = project.service<PerforatorSettingsService>()
            settings.isVisible = false

            ApplicationManager.getApplication().invokeLater {
                val fem = FileEditorManager.getInstance(project)
                for (editor in fem.allEditors) {
                    if (editor is TextEditor) {
                        removeFromEditor(editor)
                    }
                }
            }

            // Reset listener flag
            isListenerRegistered = false
        }

        /**
         * Removes toolbar from specific editor
         */
        private fun removeFromEditor(editor: TextEditor) {
            val editorComponent = editor.editor.component
            var parent = editorComponent.parent

            repeat(10) {
                if (parent is Container) {
                    for (component in parent.components) {
                        if (component is PerforatorToolbar) {
                            component.unregisterToolbar()
                            parent.remove(component)
                            parent.revalidate()
                            parent.repaint()
                            return
                        }
                    }
                }
                parent = parent.parent ?: return
            }
        }

        /**
         * Synchronizes all toolbars with current settings
         * @param excludeToolbar Optional toolbar to exclude from sync
         */
        fun syncAllToolbars(project: Project, excludeToolbar: PerforatorToolbar? = null) {
            ApplicationManager.getApplication().invokeLater {
                allToolbars.filter {
                    it.project == project && it != excludeToolbar
                }.forEach { toolbar ->
                    toolbar.loadCurrentValuesQuietly()
                }
            }
        }
    }

    /**
     * Registers this toolbar in the global tracker
     */
    private fun registerToolbar() {
        allToolbars.add(this)
    }

    /**
     * Unregisters this toolbar from the global tracker
     */
    private fun unregisterToolbar() {
        allToolbars.remove(this)
    }

    /**
     * Sets up the UI layout and components
     */
    private fun setupUI() {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(4, 8)
        )
        layout = BorderLayout()

        // Content panel with responsive wrapping layout
        val contentPanel = object : JPanel() {
            // Extract FlowLayout as a property to avoid scope issues
            private val wrappingLayout = object : FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)) {
                override fun preferredLayoutSize(parent: Container?): Dimension {
                    if (parent == null) return Dimension(0, 0)
                    return calculateWrapSize(parent)
                }

                override fun minimumLayoutSize(parent: Container?): Dimension {
                    if (parent == null) return Dimension(0, 0)
                    return calculateWrapSize(parent)
                }

                /**
                 * Calculates size with wrapping support
                 */
                private fun calculateWrapSize(parent: Container): Dimension {
                    val parentInsets = parent.insets
                    val parentWidth = parent.width

                    if (parentWidth == 0) {
                        val singleRowWidth = parent.components.filter { it.isVisible }
                            .sumOf { it.preferredSize.width + hgap } + hgap
                        val maxHeight = parent.components.filter { it.isVisible }
                            .maxOfOrNull { it.preferredSize.height } ?: 24
                        return Dimension(singleRowWidth, maxHeight + vgap * 2)
                    }

                    val closeButtonSpace = JBUI.scale(32)
                    val availableWidth = parentWidth - parentInsets.left - parentInsets.right - closeButtonSpace

                    var currentRowWidth = 0
                    var totalHeight = vgap
                    var currentRowHeight = 0
                    var totalWidth = 0

                    for (component in parent.components) {
                        if (!component.isVisible) continue

                        val compSize = component.preferredSize
                        val compWidth = compSize.width
                        val compHeight = compSize.height

                        // Check if component needs to wrap to next row
                        if (currentRowWidth > 0 && currentRowWidth + hgap + compWidth > availableWidth) {
                            totalWidth = maxOf(totalWidth, currentRowWidth)
                            totalHeight += currentRowHeight + vgap
                            currentRowWidth = compWidth
                            currentRowHeight = compHeight
                        } else {
                            if (currentRowWidth > 0) currentRowWidth += hgap
                            currentRowWidth += compWidth
                            currentRowHeight = maxOf(currentRowHeight, compHeight)
                        }
                    }

                    if (currentRowWidth > 0) {
                        totalWidth = maxOf(totalWidth, currentRowWidth)
                        totalHeight += currentRowHeight + vgap
                    }

                    return Dimension(totalWidth, totalHeight)
                }
            }

            init {
                isOpaque = false
                layout = wrappingLayout
            }

            override fun getPreferredSize(): Dimension {
                return wrappingLayout.preferredLayoutSize(this)
            }
        }

        // Add all components (visibility controlled dynamically)
        val profileLabel = JLabel("Profile:")
        val timeLabel = JLabel("Time:")
        val thresholdLabel = JLabel("Hot Threshold:")

        contentPanel.add(profileLabel)
        contentPanel.add(profileTypeCombo)
        contentPanel.add(timeLabel)
        contentPanel.add(timeCombo)
        contentPanel.add(thresholdLabel)
        contentPanel.add(thresholdField)
        contentPanel.add(thresholdUnitLabel)
        contentPanel.add(applyButton)

        // Store component groups for visibility control
        pyroscopeComponents = listOf(profileLabel, profileTypeCombo, timeLabel, timeCombo)
        thresholdComponents = listOf(thresholdLabel, thresholdField, thresholdUnitLabel, applyButton)

        // Close button panel
        val closePanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = JBUI.size(32, 24)
            maximumSize = JBUI.size(32, 24)
            minimumSize = JBUI.size(32, 24)

            closeButton.apply {
                preferredSize = JBUI.size(24, 24)
                font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(11))
                isFocusPainted = false
                border = JBUI.Borders.empty()
                isContentAreaFilled = false
            }

            add(closeButton, BorderLayout.CENTER)
        }

        add(contentPanel, BorderLayout.CENTER)
        add(closePanel, BorderLayout.EAST)

        // Responsive behavior
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                contentPanel.invalidate()
                SwingUtilities.invokeLater {
                    val parent = this@PerforatorToolbar.parent
                    parent?.let {
                        it.revalidate()
                        it.repaint()
                    }
                }
                revalidate()
                repaint()
            }
        })

        updateUIForDataSource()
    }

    /**
     * Sets up action listeners for UI components
     */
    private fun setupActions() {
        // Profile type change handler
        profileTypeCombo.addActionListener {
            if (!isUpdating) {
                updateThresholdDisplay()

                // Sync settings and other toolbars
                val settings = project.service<PerforatorSettingsService>()
                settings.profileType = profileTypeCombo.selectedItem as String
                syncAllToolbars(project, this)
            }
        }

        // Apply button handler
        applyButton.addActionListener {
            if (isUpdating) return@addActionListener

            val profileType = profileTypeCombo.selectedItem as String
            val time = timeCombo.selectedItem as String
            val thresholdText = thresholdField.text.trim()
            val normalizedThresholdText = thresholdText.replace(",", ".")
            val threshold = normalizedThresholdText.toDoubleOrNull()

            // Validate threshold
            if (threshold == null || threshold < 0.001 || threshold > 10000.0) {
                val unit = if (profileType == "Memory") "MB" else "ms"
                val minValue = if (profileType == "Memory") 0.01 else 0.001
                com.intellij.openapi.ui.Messages.showWarningDialog(
                    project,
                    "Hot threshold must be a number between $minValue and 10000 $unit",
                    "Perforator"
                )
                return@addActionListener
            }

            val settings = project.service<PerforatorSettingsService>()
            val service = project.service<PerforatorService>()
            val hasExistingData = service.hasData()

            if (hasExistingData && isDataFromFile()) {
                // Working with existing file data - keep FILE source
                val error = settings.validateAndSet(
                    profileType = profileType,
                    timeWindow = time,
                    hotThresholdCpu = if (profileType == "Memory") settings.hotThresholdCpu else threshold,
                    hotThresholdMemory = if (profileType == "Memory") threshold else settings.hotThresholdMemory
                )

                if (error != null) {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project,
                        error,
                        "Perforator: Invalid Settings"
                    )
                    return@addActionListener
                }

                // Re-render gutters with new threshold
                service.clearAllGutters(project)
                service.triggerRefresh(project)
                syncAllToolbars(project)
            } else {
                // Fetching from Pyroscope - set PYROSCOPE source
                applyButton.text = "Loading..."
                applyButton.isEnabled = false

                AppExecutorUtil.getAppExecutorService().submit {
                    val error = settings.validateAndSet(
                        profileType = profileType,
                        timeWindow = time,
                        hotThresholdCpu = if (profileType == "Memory") settings.hotThresholdCpu else threshold,
                        hotThresholdMemory = if (profileType == "Memory") threshold else settings.hotThresholdMemory
                    )

                    if (error != null) {
                        ApplicationManager.getApplication().invokeLater {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                project,
                                error,
                                "Perforator: Invalid Settings"
                            )
                            resetApplyButton()
                        }
                        return@submit
                    }

                    val query = settings.buildPyroscopeQuery()
                    val success = service.loadFromPyroscope(project, settings.baseUrl, query, time)

                    ApplicationManager.getApplication().invokeLater {
                        resetApplyButton()
                        if (success) {
                            // Mark data source as PYROSCOPE
                            settings.setDataSourcePyroscope()
                            service.clearAllGutters(project)
                            service.triggerRefresh(project)
                            syncAllToolbars(project)
                        } else {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                project,
                                "Failed to fetch from Pyroscope. Check URL, profile type, service name and time window in settings.",
                                "Perforator Error"
                            )
                        }
                    }
                }
            }
        }

        // Close button hides ALL toolbars and clears data
        closeButton.addActionListener {
            hideAllToolbarsAndClearGutters()
        }
    }

    /**
     * Resets apply button to default state
     */
    private fun resetApplyButton() {
        applyButton.text = "Apply"
        applyButton.isEnabled = true
    }

    /**
     * Loads current settings into UI (with external trigger)
     */
    fun loadCurrentValues() {
        loadCurrentValuesQuietly()
    }

    /**
     * Loads current settings into UI (internal)
     */
    private fun loadCurrentValuesQuietly() {
        if (isUpdating) return

        isUpdating = true
        try {
            val settings = project.service<PerforatorSettingsService>()

            profileTypeCombo.selectedItem = settings.profileType
            timeCombo.selectedItem = settings.timeWindow

            updateThresholdDisplay()
            updateUIForDataSource()
        } finally {
            isUpdating = false
        }
    }

    /**
     * Updates threshold field based on current profile type
     */
    private fun updateThresholdDisplay() {
        val settings = project.service<PerforatorSettingsService>()
        val profileType = profileTypeCombo.selectedItem as String

        when (profileType) {
            "Memory" -> {
                thresholdField.text = formatDecimalValue(settings.hotThresholdMemory)
                thresholdUnitLabel.text = "MB"
            }
            else -> {
                thresholdField.text = formatDecimalValue(settings.hotThresholdCpu)
                thresholdUnitLabel.text = "ms"
            }
        }
    }

    /**
     * Formats decimal values for display
     */
    private fun formatDecimalValue(value: Double): String {
        return if (value == value.toInt().toDouble()) {
            value.toInt().toString()
        } else {
            String.format("%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    /**
     * Updates UI based on data source (Pyroscope vs File)
     */
    private fun updateUIForDataSource() {
        val isFileData = isDataFromFile()

        // Show/hide components based on data source
        pyroscopeComponents.forEach { component ->
            component.isVisible = !isFileData // Show for Pyroscope, hide for file data
        }

        // Threshold components are always visible
        thresholdComponents.forEach { component ->
            component.isVisible = true
        }

        // Update apply button behavior
        if (isFileData) {
            applyButton.text = "Apply"
            applyButton.toolTipText = "Apply threshold changes to file data"
        } else {
            applyButton.text = "Apply"
            applyButton.toolTipText = "Fetch data from Pyroscope or apply threshold changes"
        }

        // Force layout recalculation
        revalidate()
        repaint()
    }

    /**
     * Checks if current data is from file (vs Pyroscope)
     */
    private fun isDataFromFile(): Boolean {
        val settings = project.service<PerforatorSettingsService>()
        return settings.dataSource == "FILE"
    }

    /**
     * Hides all toolbars and clears profiling data
     */
    private fun hideAllToolbarsAndClearGutters() {
        val service = project.service<PerforatorService>()

        // Clear data
        service.clearData()
        service.clearAllGutters(project)
        service.triggerRefresh(project)

        // Hide toolbars from all editors
        hideFromAllEditors(project)
    }
}