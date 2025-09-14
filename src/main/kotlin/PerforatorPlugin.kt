@file:Suppress("UnstableApiUsage")

package com.example.perforator

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FilenameIndex
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.Timer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

// ----------------------------
// Data & services
// ----------------------------

data class ProfilingData(val line: Int, val executionTime: Long, val samples: Int)

@Service
@State(
    name = "PerforatorSettings",
    storages = [Storage("perforator.xml")]
)
class PerforatorSettingsService : PersistentStateComponent<PerforatorSettingsService.State> {
    data class State(
        var baseUrl: String = "http://localhost:4040",
        var profileType: String = "CPU",
        var serviceName: String = "my_application_name",
        var timeWindow: String = "now-15m",
        var hotThresholdCpu: Int = 50,      // milliseconds
        var hotThresholdMemory: Int = 10,   // megabytes
        var isVisible: Boolean = false,
        var basicAuthUsername: String = "", // Basic auth username (optional)
        var basicAuthPassword: String = ""  // Basic auth password (optional)
    )

    private var myState = State()

    var baseUrl: String
        get() = myState.baseUrl
        set(value) { myState.baseUrl = value }

    var profileType: String
        get() = myState.profileType
        set(value) { myState.profileType = value }

    var serviceName: String
        get() = myState.serviceName
        set(value) { myState.serviceName = value }

    var timeWindow: String
        get() = myState.timeWindow
        set(value) { myState.timeWindow = value }

    var hotThresholdCpu: Int
        get() = myState.hotThresholdCpu
        set(value) { myState.hotThresholdCpu = value }

    var hotThresholdMemory: Int
        get() = myState.hotThresholdMemory
        set(value) { myState.hotThresholdMemory = value }

    var isVisible: Boolean
        get() = myState.isVisible
        set(value) { myState.isVisible = value }

    var basicAuthUsername: String
        get() = myState.basicAuthUsername
        set(value) { myState.basicAuthUsername = value }

    var basicAuthPassword: String
        get() = myState.basicAuthPassword
        set(value) { myState.basicAuthPassword = value }

    // Computed property that returns the appropriate threshold based on profile type
    val currentHotThreshold: Int
        get() = when (profileType) {
            "Memory" -> hotThresholdMemory
            else -> hotThresholdCpu
        }

    // Check if basic auth is enabled
    val hasBasicAuth: Boolean
        get() = basicAuthUsername.isNotBlank() && basicAuthPassword.isNotBlank()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun buildQuery(): String {
        return when (profileType) {
            "CPU" -> "process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"$serviceName\"}"
            "Memory" -> "memory:alloc_in_new_tlab_bytes:bytes:space:bytes{service_name=\"$serviceName\"}"
            else -> "process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"$serviceName\"}"
        }
    }

    fun validateAndSet(
        baseUrl: String,
        profileType: String,
        serviceName: String,
        timeWindow: String,
        hotThresholdCpu: Int,
        hotThresholdMemory: Int,
        basicAuthUsername: String = "",
        basicAuthPassword: String = ""
    ): String? {
        val url = baseUrl.trim().removeSuffix("/")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "Base URL must start with http:// or https://"

        if (profileType !in setOf("CPU", "Memory")) return "Profile type must be either CPU or Memory"

        if (serviceName.trim().isEmpty()) return "Service name cannot be empty"

        val allowed = setOf("now-15m","now-30m","now-1h","now-1d","now-3d","now-1w")
        if (timeWindow !in allowed) return "Time must be one of: ${allowed.joinToString(", ")}"

        if (hotThresholdCpu < 1 || hotThresholdCpu > 10000) return "CPU hot threshold must be between 1 and 10000 ms"
        if (hotThresholdMemory < 1 || hotThresholdMemory > 10000) return "Memory hot threshold must be between 1 and 10000 MB"

        // Validate basic auth: if username is provided, password must also be provided
        val username = basicAuthUsername.trim()
        val password = basicAuthPassword.trim()
        if (username.isNotEmpty() && password.isEmpty()) return "Basic auth password is required when username is provided"

        this.baseUrl = url
        this.profileType = profileType
        this.serviceName = serviceName.trim()
        this.timeWindow = timeWindow
        this.hotThresholdCpu = hotThresholdCpu
        this.hotThresholdMemory = hotThresholdMemory
        this.basicAuthUsername = username
        this.basicAuthPassword = password
        return null
    }

    // Overloaded method for updating just the current threshold (keeping auth unchanged)
    fun validateAndSetCurrentThreshold(baseUrl: String, profileType: String, serviceName: String, timeWindow: String, threshold: Int): String? {
        return when (profileType) {
            "Memory" -> validateAndSet(baseUrl, profileType, serviceName, timeWindow, hotThresholdCpu, threshold, basicAuthUsername, basicAuthPassword)
            else -> validateAndSet(baseUrl, profileType, serviceName, timeWindow, threshold, hotThresholdMemory, basicAuthUsername, basicAuthPassword)
        }
    }

    // Legacy compatibility - kept for backwards compatibility with existing code
    val pyroscopeQuery: String
        get() = buildQuery()

    // Legacy compatibility
    @Deprecated("Use currentHotThreshold instead")
    val hotThresholdMs: Int
        get() = currentHotThreshold
}

@Service
class PerforatorService {
    // filename (basename) -> (line -> data)
    private val profilingData = ConcurrentHashMap<String, MutableMap<Int, ProfilingData>>()
    private val version = AtomicLong(0L)

    fun loadFromPyroscope(project: Project, baseUrl: String, query: String, fromWindow: String): Boolean {
        return try {
            val settings = project.service<PerforatorSettingsService>()
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
            val fullUrl = "$baseUrl/pyroscope/render?query=$encodedQuery&from=$fromWindow"
            println("PERFORATOR: fetching $fullUrl")

            val jsonText = if (settings.hasBasicAuth) {
                // Make request with basic auth
                val credentials = "${settings.basicAuthUsername}:${settings.basicAuthPassword}"
                val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())

                HttpRequests.request(fullUrl)
                    .tuner { connection ->
                        connection.setRequestProperty("Authorization", "Basic $encodedCredentials")
                    }
                    .connect { it.readString() }
            } else {
                // Make request without auth
                HttpRequests.request(fullUrl).connect { it.readString() }
            }

            val reader = JsonFlamebearerReader(project)
            val byFile = reader.readText(jsonText)
            profilingData.clear()
            profilingData.putAll(byFile)
            version.incrementAndGet()
            println("PERFORATOR: fetched and parsed ${profilingData.size} files")
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    fun getProfilingData(filename: String): Map<Int, ProfilingData>? = profilingData[filename]
    fun hasData(): Boolean = profilingData.isNotEmpty()
    fun getVersion(): Long = version.get()
    fun clearData() {
        profilingData.clear()
        version.incrementAndGet()
    }
}

// ----------------------------
// File Editor Listener for Toolbar Management
// ----------------------------

class PerforatorFileEditorListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val settings = project.service<PerforatorSettingsService>()
        if (settings.isVisible) {
            // Add toolbar to newly opened editor if toolbar should be visible
            ApplicationManager.getApplication().invokeLater {
                val editor = source.getSelectedEditor(file) as? TextEditor ?: return@invokeLater
                PerforatorToolbar.addToEditor(project, editor)
            }
        }
    }
}

// ----------------------------
// Perforator Toolbar
// ----------------------------

class PerforatorToolbar(private val project: Project) : JPanel(BorderLayout()) {
    private val profileTypeCombo = JComboBox(arrayOf("CPU", "Memory"))
    private val timeCombo = JComboBox(arrayOf("now-15m","now-30m","now-1h","now-1d","now-3d","now-1w"))
    private val thresholdField = JTextField()
    private val thresholdUnitLabel = JLabel()
    private val applyButton = JButton("Apply")
    private val closeButton = JButton("×")

    init {
        setupUI()
        setupActions()
        loadCurrentValues()
    }

    private fun setupUI() {
        background = UIUtil.getPanelBackground()
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.GRAY),
            JBUI.Borders.empty(8, 12)
        )

        // Main content panel with responsive flow layout that wraps
        val contentPanel = JPanel()
        contentPanel.background = background
        contentPanel.layout = object : FlowLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(4)) {
            override fun preferredLayoutSize(parent: Container?): Dimension {
                return calculateLayoutSize(parent)
            }

            private fun calculateLayoutSize(parent: Container?): Dimension {
                parent?.let { p ->
                    val insets = p.insets
                    val availableWidth = p.width - insets.left - insets.right - JBUI.scale(60) // Reserve space for close button

                    if (availableWidth <= 0) {
                        // Not yet laid out, return estimated size
                        val componentHeight = JBUI.scale(28)
                        return Dimension(JBUI.scale(600), componentHeight + vgap * 2)
                    }

                    var currentRowWidth = 0
                    var currentRowHeight = 0
                    var totalHeight = vgap // Top margin
                    var maxRowWidth = 0

                    for (i in 0 until p.componentCount) {
                        val comp = p.getComponent(i)
                        if (!comp.isVisible) continue

                        val d = comp.preferredSize
                        val compWidth = d.width + hgap
                        val compHeight = d.height

                        // Check if component fits in current row
                        if (currentRowWidth + compWidth > availableWidth && currentRowWidth > 0) {
                            // Move to next row
                            totalHeight += currentRowHeight + vgap
                            maxRowWidth = Math.max(maxRowWidth, currentRowWidth)
                            currentRowWidth = compWidth
                            currentRowHeight = compHeight
                        } else {
                            // Add to current row
                            currentRowWidth += compWidth
                            currentRowHeight = Math.max(currentRowHeight, compHeight)
                        }
                    }

                    // Add the last row
                    if (currentRowWidth > 0) {
                        totalHeight += currentRowHeight
                        maxRowWidth = Math.max(maxRowWidth, currentRowWidth)
                    }

                    totalHeight += vgap // Bottom margin

                    return Dimension(Math.max(maxRowWidth, JBUI.scale(400)), totalHeight)
                }

                // Fallback if parent is null
                val componentHeight = JBUI.scale(28)
                return Dimension(JBUI.scale(600), componentHeight + vgap * 2)
            }

            override fun layoutContainer(parent: Container) {
                if (parent.width <= 0) return
                val insets = parent.insets
                val availableWidth = parent.width - insets.left - insets.right - JBUI.scale(60) // Reserve space for close button

                var x = insets.left + hgap
                var y = insets.top + vgap
                var currentRowWidth = 0
                var currentRowHeight = 0
                val componentsInCurrentRow = mutableListOf<Component>()

                fun layoutCurrentRow() {
                    if (componentsInCurrentRow.isEmpty()) return

                    // Center components vertically in the row
                    val rowCenterY = y + currentRowHeight / 2
                    var rowX = insets.left + hgap

                    for (comp in componentsInCurrentRow) {
                        val d = comp.preferredSize
                        val centeredY = rowCenterY - d.height / 2
                        comp.setBounds(rowX, centeredY, d.width, d.height)
                        rowX += d.width + hgap
                    }

                    // Move to next row
                    y += currentRowHeight + vgap
                    currentRowWidth = 0
                    currentRowHeight = 0
                    componentsInCurrentRow.clear()
                }

                for (i in 0 until parent.componentCount) {
                    val comp = parent.getComponent(i)
                    if (!comp.isVisible) continue

                    val d = comp.preferredSize
                    val compWidth = d.width + hgap

                    // Check if component fits in current row
                    if (currentRowWidth + compWidth > availableWidth && currentRowWidth > 0) {
                        // Layout current row and start new one
                        layoutCurrentRow()
                    }

                    // Add component to current row
                    componentsInCurrentRow.add(comp)
                    currentRowWidth += compWidth
                    currentRowHeight = Math.max(currentRowHeight, d.height)
                }

                // Layout the last row
                layoutCurrentRow()
            }
        }

        // Profile Type selector - fixed 120px width
        contentPanel.add(JLabel("Profile:"))
        profileTypeCombo.preferredSize = JBUI.size(120, 28)
        contentPanel.add(profileTypeCombo)

        // Time selector - fixed 120px width
        contentPanel.add(JLabel("Time:"))
        timeCombo.preferredSize = JBUI.size(120, 28)
        contentPanel.add(timeCombo)

        // Threshold field - fixed width
        contentPanel.add(JLabel("Hot Threshold:"))
        thresholdField.preferredSize = JBUI.size(70, 28)
        thresholdField.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        contentPanel.add(thresholdField)

        thresholdUnitLabel.preferredSize = JBUI.size(30, 28)
        thresholdUnitLabel.toolTipText = "Threshold for highlighting hot lines"
        contentPanel.add(thresholdUnitLabel)

        // Apply button - fixed width
        applyButton.preferredSize = JBUI.size(80, 28)
        applyButton.putClientProperty("JButton.buttonType", "default")
        applyButton.putClientProperty("defaultButton", true)
        contentPanel.add(applyButton)

        // Create main layout
        layout = BorderLayout()
        add(contentPanel, BorderLayout.CENTER)

        // Close button panel - always stays on the right
        val closePanel = JPanel()
        closePanel.background = background
        closePanel.layout = BorderLayout()
        closePanel.preferredSize = JBUI.size(40, 28)

        closeButton.preferredSize = JBUI.size(24, 24)
        closeButton.font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(14))
        closeButton.isFocusPainted = false
        closeButton.border = JBUI.Borders.empty()
        closeButton.isContentAreaFilled = false

        closePanel.add(closeButton, BorderLayout.CENTER)
        add(closePanel, BorderLayout.EAST)

        // Add component listener to trigger revalidation when resized
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                // Just revalidate to trigger the responsive layout
                contentPanel.revalidate()
                repaint()
            }
        })
    }

    private fun setupActions() {
        // Update threshold field and unit when profile type changes
        profileTypeCombo.addActionListener {
            updateThresholdDisplay()
        }

        applyButton.addActionListener {
            val profileType = profileTypeCombo.selectedItem as String
            val time = timeCombo.selectedItem as String
            val thresholdText = thresholdField.text.trim()

            val threshold = thresholdText.toIntOrNull()
            if (threshold == null || threshold < 1 || threshold > 10000) {
                val unit = if (profileType == "Memory") "MB" else "ms"
                Messages.showWarningDialog(project, "Hot threshold must be a number between 1 and 10000 $unit", "Perforator")
                return@addActionListener
            }

            applyButton.text = "Loading..."
            applyButton.isEnabled = false

            AppExecutorUtil.getAppExecutorService().submit {
                val settings = project.service<PerforatorSettingsService>()
                val service = project.service<PerforatorService>()

                // Store the old profile type to detect changes
                val oldProfileType = settings.profileType

                // Use existing serviceName from settings, just update the toolbar values
                val error = settings.validateAndSetCurrentThreshold(settings.baseUrl, profileType, settings.serviceName, time, threshold)
                if (error != null) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, error, "Perforator: Invalid Settings")
                        resetApplyButton()
                    }
                    return@submit
                }

                val query = settings.buildQuery()
                val success = service.loadFromPyroscope(project, settings.baseUrl, query, time)

                ApplicationManager.getApplication().invokeLater {
                    resetApplyButton()
                    if (success) {
                        // If profile type changed, clear all gutters first to prevent mixed formatting
                        if (oldProfileType != profileType) {
                            clearAllGutters(project)
                        }
                        triggerRefresh(project)
                        // Update all existing toolbars with new settings
                        updateAllToolbars(project)
                        // Removed success dialog as requested
                    } else {
                        Messages.showErrorDialog(project, "Failed to fetch from Pyroscope.\nCheck URL, profile type, service name and time window in settings.", "Perforator Error")
                    }
                }
            }
        }

        closeButton.addActionListener {
            hideAllToolbarsAndClearGutters()
        }
    }

    private fun updateThresholdDisplay() {
        val settings = project.service<PerforatorSettingsService>()
        val profileType = profileTypeCombo.selectedItem as String

        when (profileType) {
            "Memory" -> {
                thresholdField.text = settings.hotThresholdMemory.toString()
                thresholdUnitLabel.text = "MB"
                thresholdUnitLabel.toolTipText = "Memory allocation threshold in megabytes"
            }
            else -> {
                thresholdField.text = settings.hotThresholdCpu.toString()
                thresholdUnitLabel.text = "ms"
                thresholdUnitLabel.toolTipText = "CPU execution time threshold in milliseconds"
            }
        }
    }

    private fun resetApplyButton() {
        applyButton.text = "Apply"
        applyButton.isEnabled = true
    }

    fun loadCurrentValues() {
        val settings = project.service<PerforatorSettingsService>()
        profileTypeCombo.selectedItem = settings.profileType
        timeCombo.selectedItem = settings.timeWindow
        updateThresholdDisplay()
    }

    fun refreshFromSettings() {
        loadCurrentValues()
    }

    private fun updateAllToolbars(project: Project) {
        val fem = FileEditorManager.getInstance(project)
        for (editor in fem.allEditors) {
            if (editor is TextEditor) {
                findToolbarInEditor(editor)?.loadCurrentValues()
            }
        }
    }

    private fun findToolbarInEditor(editor: TextEditor): PerforatorToolbar? {
        val editorComponent = editor.editor.component
        var parent = editorComponent.parent
        repeat(5) {
            if (parent is Container) {
                for (component in parent.components) {
                    if (component is PerforatorToolbar) {
                        return component
                    }
                }
                parent = parent.parent
            } else {
                return null
            }
        }
        return null
    }

    private fun hideAllToolbarsAndClearGutters() {
        val settings = project.service<PerforatorSettingsService>()
        val service = project.service<PerforatorService>()
        settings.isVisible = false
        service.clearData()

        // Remove toolbar from all editors
        val fem = FileEditorManager.getInstance(project)
        for (editor in fem.allEditors) {
            if (editor is TextEditor) {
                removeFromEditor(editor)
            }
        }

        // Clear all gutters
        clearAllGutters(project)
        triggerRefresh(project)
    }

    private fun removeFromEditor(editor: TextEditor) {
        val editorComponent = editor.editor.component
        var parent = editorComponent.parent
        repeat(5) {
            if (parent is Container) {
                for (component in parent.components) {
                    if (component is PerforatorToolbar) {
                        parent.remove(component)
                        parent.revalidate()
                        parent.repaint()
                        println("PERFORATOR: Toolbar removed from editor")
                        return
                    }
                }
                parent = parent.parent
            } else {
                return
            }
        }
    }

    companion object {
        fun showInAllEditors(project: Project) {
            val settings = project.service<PerforatorSettingsService>()
            settings.isVisible = true
            ApplicationManager.getApplication().invokeLater {
                val fem = FileEditorManager.getInstance(project)
                // Add toolbar to all currently open editors
                for (editor in fem.allEditors) {
                    if (editor is TextEditor) {
                        addToEditor(project, editor)
                    }
                }

                // Register listener for new editors
                val connection = project.messageBus.connect()
                connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, PerforatorFileEditorListener(project))
                println("PERFORATOR: Toolbars added to all editors and listener registered")
            }
        }

        fun addToEditor(project: Project, editor: TextEditor) {
            try {
                val editorComponent = editor.editor.component
                // Try to find the main editor panel
                var targetPanel: JPanel? = null
                var parent = editorComponent.parent

                // Look for the main editor panel (usually 2-3 levels up)
                repeat(5) {
                    when (parent) {
                        is JPanel -> {
                            targetPanel = parent
                            return@repeat
                        }
                        is Container -> parent = parent.parent
                        else -> return@repeat
                    }
                }

                if (targetPanel == null) {
                    return
                }

                // Check if toolbar already exists
                for (component in targetPanel.components) {
                    if (component is PerforatorToolbar) {
                        return // Toolbar already exists
                    }
                }

                // Create and add toolbar
                val toolbar = PerforatorToolbar(project)
                targetPanel.add(toolbar, BorderLayout.NORTH)
                targetPanel.revalidate()
                targetPanel.repaint()
            } catch (e: Exception) {
                println("PERFORATOR: Error adding toolbar to editor: ${e.message}")
            }
        }
    }
}

// ----------------------------
// Symbol resolution (package/class-only -> file:line)
// ----------------------------

private class SymbolResolver(private val project: Project) {
    private val cache = HashMap<String, Pair<String, Int>?>()
    private val searchScope: GlobalSearchScope = GlobalSearchScope.allScope(project)

    fun resolve(symbol: String): Pair<String, Int>? {
        return cache.getOrPut(symbol) {
            ApplicationManager.getApplication().runReadAction<Pair<String, Int>?> {
                val candidate = extractFqnAndMethod(symbol) ?: return@runReadAction null
                val (classFqn, methodName) = candidate

                val javaFacade = JavaPsiFacade.getInstance(project)
                val psiClass = javaFacade.findClass(classFqn, searchScope)
                if (psiClass != null) {
                    val methods: Array<PsiMethod> = psiClass.findMethodsByName(methodName, true)
                    val m: PsiMethod? = methods.firstOrNull()
                    if (m != null) {
                        methodLocation(m)?.let { return@runReadAction it }
                    }
                }

                // Optional Kotlin by reflection
                resolveKotlinReflective(classFqn, methodName)
            }
        }
    }

    private fun methodLocation(m: PsiMethod): Pair<String, Int>? {
        val nav: PsiElement? = runCatching { m.navigationElement }.getOrNull()
        val target: PsiElement = when {
            nav?.containingFile != null -> nav
            else -> runCatching { m.originalElement }.getOrNull() ?: return null
        }

        val psiFile: PsiFile = target.containingFile ?: return null
        val vFile = psiFile.virtualFile ?: return null
        if (!vFile.isInLocalFileSystem) return null

        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val offset = runCatching { target.textOffset }.getOrDefault(-1)
        if (offset < 0) return null

        val line0 = runCatching { doc.getLineNumber(offset) }.getOrNull() ?: return null
        return vFile.name to (line0 + 1)
    }

    private fun extractFqnAndMethod(symbol: String): Pair<String, String>? {
        val s = symbol.trim().replace('/', '.')
        val base = s.substringBefore(' ')
        val lastDot = base.lastIndexOf('.')
        if (lastDot <= 0) return null

        val classPart = base.substring(0, lastDot)
        val methodPart = base.substring(lastDot + 1).takeWhile { ch ->
            ch == '_' || ch == '$' || ch.isLetterOrDigit()
        }

        if (classPart.isEmpty() || methodPart.isEmpty()) return null
        return classPart to methodPart
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveKotlinReflective(classFqn: String, methodName: String): Pair<String, Int>? {
        return try {
            val idxClazz = Class.forName("org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex")
            val getInstance = idxClazz.getMethod("getInstance")
            val index = getInstance.invoke(null)

            val getMethod = idxClazz.getMethod("get", String::class.java, Project::class.java, GlobalSearchScope::class.java)
            val functions = getMethod.invoke(index, methodName, project, searchScope) as? Collection<*> ?: return null

            for (f in functions) {
                val ktFile = f?.javaClass?.getMethod("getContainingFile")?.invoke(f) as? PsiFile ?: continue

                val fqName = runCatching {
                    val fq = f.javaClass.getMethod("getFqName").invoke(f)
                    fq?.javaClass?.getMethod("asString")?.invoke(fq) as? String
                }.getOrNull()

                val parent = runCatching { f.javaClass.getMethod("getParent").invoke(f) }.getOrNull()
                val parentName = (parent as? PsiNamedElement)?.name

                val matchesClass = fqName?.contains(classFqn) == true ||
                        (parentName != null && classFqn.endsWith(".$parentName"))

                if (!matchesClass) continue

                val vFile = ktFile.virtualFile ?: continue
                if (!vFile.isInLocalFileSystem) continue

                val doc = PsiDocumentManager.getInstance(project).getDocument(ktFile) ?: continue
                val textOffset = (runCatching {
                    f.javaClass.getMethod("getTextOffset").invoke(f) as? Int
                }.getOrNull()) ?: -1
                if (textOffset < 0) continue

                val line0 = runCatching { doc.getLineNumber(textOffset) }.getOrNull() ?: continue
                return ktFile.name to (line0 + 1)
            }

            null
        } catch (_: Throwable) {
            null
        }
    }
}

// ----------------------------
// Flamebearer JSON parsing
// ----------------------------

@Serializable
private data class FlamebearerV1(
    val names: List<String>,
    val levels: List<List<Long>>,
    val numTicks: Long? = null,
    val maxSelf: Long? = null,
    val sampleRate: Long? = null,
    val units: String? = null,
    val format: String? = null
)

@Serializable
private data class FlamebearerEnvelope(
    val flamebearer: FlamebearerV1,
    val metadata: Map<String, String>? = null
)

private class JsonFlamebearerReader(private val project: Project) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val fileLineRegex = Regex("""([^\\/\s:]+?\.\w+):(\d+)(?::\d+)?""")
    private val resolver = SymbolResolver(project)

    fun read(file: File): MutableMap<String, MutableMap<Int, ProfilingData>> =
        readText(file.readText())

    fun readText(text: String): MutableMap<String, MutableMap<Int, ProfilingData>> {
        println("PERFORATOR: parsing JSON length=${text.length}")
        val env = parseEnvelopeKotlinx(text)
        return aggregate(env.flamebearer)
    }

    private fun aggregate(fb: FlamebearerV1): MutableMap<String, MutableMap<Int, ProfilingData>> {
        val names = fb.names
        val levels: List<List<Long>> = fb.levels
        val byFile = mutableMapOf<String, MutableMap<Int, ProfilingData>>()
        val chunkSize = 4
        var totalNodes = 0
        var matched = 0
        val samplePrinted = HashSet<String>()

        for (row: List<Long> in levels) {
            var i = 0
            val size = row.size
            while (i + chunkSize <= size) {
                totalNodes++
                val self: Long = row[i + 2]
                val nameIdxLong: Long = row[i + 3]
                val nameIdx = nameIdxLong.toInt()
                val symbol = names.getOrNull(nameIdx) ?: ""

                var loc: Pair<String, Int>? = parseFileAndLine(symbol)
                if (loc == null) {
                    val i1 = symbol.lastIndexOf('(')
                    val i2 = symbol.lastIndexOf(')')
                    if (i1 >= 0 && i2 > i1) {
                        val inside = symbol.substring(i1 + 1, i2)
                        loc = parseFileAndLine(inside)
                    }
                }

                if (loc == null) {
                    loc = resolver.resolve(symbol)
                }

                if (loc != null) {
                    matched++
                    val (baseFile, line) = loc
                    if (samplePrinted.size < 5) {
                        println("PERFORATOR: match $baseFile:$line from symbol='$symbol'")
                        samplePrinted.add("$baseFile:$line")
                    }

                    val lineMap = byFile.getOrPut(baseFile) { mutableMapOf() }
                    val prev = lineMap[line]
                    val add = if (self >= 0L) self else 0L
                    lineMap[line] = if (prev == null) ProfilingData(line, add, 1)
                    else prev.copy(executionTime = prev.executionTime + add, samples = prev.samples + 1)
                }

                i += chunkSize
            }
        }

        println("PERFORATOR: nodes=$totalNodes matched=$matched files=${byFile.keys.take(5)}")
        return byFile
    }

    private fun parseFileAndLine(symbol: String): Pair<String, Int>? {
        fileLineRegex.find(symbol)?.let { m ->
            val file = m.groupValues[1]
            val line = m.groupValues[2].toIntOrNull() ?: return null
            return file to line
        }
        return null
    }

    private fun parseEnvelopeKotlinx(text: String): FlamebearerEnvelope {
        return try {
            json.decodeFromString<FlamebearerEnvelope>(text)
        } catch (_: Throwable) {
            val root = json.parseToJsonElement(text)
            val obj = root as? JsonObject ?: throw IllegalArgumentException("Invalid JSON: expected object")

            val fbElem: JsonElement = obj["flamebearer"] ?: root
            val fbObj: JsonObject = fbElem.jsonObject

            val names: List<String> = fbObj["names"]?.jsonArray?.let { arr ->
                buildList(arr.size) { for (je in arr) add(je.jsonPrimitive.content) }
            } ?: emptyList()

            val levels: List<List<Long>> = fbObj["levels"]?.jsonArray?.let { rows ->
                buildList<List<Long>>(rows.size) {
                    for (rowElem in rows) {
                        val rowArr: JsonArray = rowElem.jsonArray
                        val longs = ArrayList<Long>(rowArr.size)
                        for (v in rowArr) longs.add(v.jsonPrimitive.long)
                        add(longs)
                    }
                }
            } ?: emptyList()

            FlamebearerEnvelope(
                flamebearer = FlamebearerV1(
                    names = names,
                    levels = levels,
                    numTicks = fbObj["numTicks"]?.jsonPrimitive?.longOrNull,
                    maxSelf = fbObj["maxSelf"]?.jsonPrimitive?.longOrNull,
                    sampleRate = fbObj["sampleRate"]?.jsonPrimitive?.longOrNull,
                    units = fbObj["units"]?.jsonPrimitive?.contentOrNull,
                    format = fbObj["format"]?.jsonPrimitive?.contentOrNull
                ),
                metadata = null
            )
        }
    }
}

// ----------------------------
// Actions: fetch only (load removed)
// ----------------------------

class FetchFromPyroscopeAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = project.service<PerforatorSettingsService>()
        val svc = project.service<PerforatorService>()

        AppExecutorUtil.getAppExecutorService().submit {
            val query = settings.buildQuery()
            val ok = svc.loadFromPyroscope(project, settings.baseUrl, query, settings.timeWindow)
            ApplicationManager.getApplication().invokeLater {
                if (ok) {
                    triggerRefresh(project)
                    PerforatorToolbar.showInAllEditors(project)
                } else {
                    Messages.showErrorDialog(project, "Failed to fetch from Pyroscope.\nCheck URL, profile type, service name and time window in settings.", "Perforator Error")
                }
            }
        }
    }
}

private fun triggerRefresh(project: Project) {
    UIUtil.invokeLaterIfNeeded {
        // First clear all existing gutters to prevent stale formatting
        clearAllGutters(project)

        val analyzer = DaemonCodeAnalyzer.getInstance(project)
        val fem = FileEditorManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        ApplicationManager.getApplication().runReadAction {
            var restartedAny = false
            for (fe: FileEditor in fem.allEditors) {
                val vFile = fe.file ?: continue
                val psi = psiManager.findFile(vFile) ?: continue
                analyzer.restart(psi)
                restartedAny = true
            }

            if (!restartedAny) analyzer.restart()
        }

        // Force revalidation of all editors
        fem.allEditors.forEach { fe ->
            (fe as? TextEditor)?.editor?.contentComponent?.let { c ->
                c.revalidate()
                c.repaint()
            }
        }
    }
}

private fun clearAllGutters(project: Project) {
    UIUtil.invokeLaterIfNeeded {
        val fem = FileEditorManager.getInstance(project)
        for (editor in fem.allEditors) {
            if (editor is TextEditor) {
                val markup = editor.editor.markupModel
                // Get all highlighters that are Perforator gutters
                val perforatorHighlighters = markup.allHighlighters.filter {
                    it.gutterIconRenderer is BadgeOnlyGutterRenderer
                }
                // Dispose them all
                perforatorHighlighters.forEach { highlighter ->
                    try {
                        highlighter.dispose()
                    } catch (e: Exception) {
                        // Ignore disposal errors
                    }
                }
            }
        }

        // Force a brief delay to ensure all gutters are cleared before refresh
        Timer(50) {
            // Trigger a final repaint after clearing
            fem.allEditors.forEach { fe ->
                (fe as? TextEditor)?.editor?.contentComponent?.repaint()
            }
        }.apply {
            isRepeats = false
            start()
        }
    }
}

// ----------------------------
// Annotator with gutter badges - UPDATED for Memory/CPU support
// ----------------------------

class PerforatorGutterAnnotator : Annotator, DumbAware {
    companion object {
        private val EMITTED_LINES_KEY = Key.create<MutableSet<Int>>("perforator.emitted.lines")
    }

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile ?: return
        val project = file.project
        val settings = project.service<PerforatorSettingsService>()

        // Don't show gutters if toolbar is not visible
        if (!settings.isVisible) return

        val svc = project.service<PerforatorService>()
        val doc: Document = element.containingFile.viewProvider.document ?: return
        val data = svc.getProfilingData(file.name) ?: return

        val lineIdx = doc.getLineNumber(element.textOffset)
        val lineStart = doc.getLineStartOffset(lineIdx)
        val line = lineIdx + 1
        val d = data[line] ?: return

        val session = holder.currentAnnotationSession
        val emitted = session.getUserData(EMITTED_LINES_KEY)
            ?: mutableSetOf<Int>().also { session.putUserData(EMITTED_LINES_KEY, it) }
        if (!emitted.add(lineStart)) return

        // Format value based on profile type
        val (label, isHot) = when (settings.profileType) {
            "Memory" -> {
                val bytes = d.executionTime
                val mb = bytes / (1024.0 * 1024.0)
                val formattedMb = if (mb >= 10) {
                    String.format("%.0f MB", mb)
                } else if (mb >= 1) {
                    String.format("%.1f MB", mb)
                } else {
                    String.format("%.2f MB", mb)
                }
                val hot = mb >= settings.hotThresholdMemory
                formattedMb to hot
            }
            else -> { // CPU
                val msLong = d.executionTime / 1_000_000L
                val ms = msLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val formattedMs = formatMs(ms)
                val hot = ms >= settings.hotThresholdCpu
                formattedMs to hot
            }
        }

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(TextRange(element.textOffset, element.textOffset))
            .create()

        val fem = FileEditorManager.getInstance(project)
        val editors = fem.allEditors.mapNotNull { (it as? TextEditor)?.editor }.filter { it.document == doc }

        for (editor in editors) {
            val markup = editor.markupModel

            // Remove existing highlighter at this position
            markup.allHighlighters
                .filter {
                    it.startOffset == lineStart &&
                            it.endOffset == lineStart &&
                            it.gutterIconRenderer is BadgeOnlyGutterRenderer
                }
                .forEach { it.dispose() }

            val rh: RangeHighlighter = markup.addRangeHighlighter(
                lineStart,
                lineStart,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                null,
                HighlighterTargetArea.LINES_IN_RANGE
            )

            // Pass project, fileName, and line number to the gutter renderer
            rh.gutterIconRenderer = BadgeOnlyGutterRenderer(
                label,
                isHot,
                alignRight = true,
                project = project,
                fileName = file.name,
                lineNumber = line
            )
        }
    }

    private fun formatMs(ms: Int): String {
        val nf = NumberFormat.getIntegerInstance()
        return "${nf.format(ms)} ms"
    }
}

private class BadgeOnlyGutterRenderer(
    private val text: String,
    private val hot: Boolean,
    private val alignRight: Boolean,
    private val project: Project,
    private val fileName: String,
    private val lineNumber: Int
) : GutterIconRenderer() {

    private val padX = 10
    private val padY = 5
    private val radius = 10f

    @Volatile private var cachedWidth: Int = -1
    @Volatile private var cachedHeight: Int = -1

    override fun getTooltipText(): String? = text
    override fun isNavigateAction(): Boolean = false

    override fun getClickAction(): AnAction? {
        // Left-click does nothing now
        return null
    }

    override fun getRightButtonClickAction(): AnAction? {
        return object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val settings = project.service<PerforatorSettingsService>()
                val profileType = if (settings.profileType == "Memory") "Memory usage" else "CPU time"

                // Try to resolve method name from the current line
                val methodReference = resolveMethodReference(project, fileName, lineNumber)

                val prompt = buildOptimizationPrompt(
                    profileType = profileType,
                    methodReference = methodReference,
                    serviceName = settings.serviceName,
                    timePeriod = settings.timeWindow
                )

                // Copy directly to clipboard
                try {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    val stringSelection = StringSelection(prompt)
                    clipboard.setContents(stringSelection, null)

                    // Show brief notification that it was copied
                    ApplicationManager.getApplication().invokeLater {
                        val notification = com.intellij.notification.Notification(
                            "Perforator",
                            "AI Prompt Copied",
                            "Optimization prompt copied to clipboard",
                            com.intellij.notification.NotificationType.INFORMATION
                        )
                        com.intellij.notification.Notifications.Bus.notify(notification, project)
                    }
                } catch (ex: Exception) {
                    println("PERFORATOR: Error copying to clipboard: ${ex.message}")
                }
            }
        }
    }

    private fun resolveMethodReference(project: Project, fileName: String, lineNumber: Int): String {
        return ApplicationManager.getApplication().runReadAction<String> {
            try {
                // Find all open files with this name
                val fem = FileEditorManager.getInstance(project)
                val psiManager = PsiManager.getInstance(project)

                for (editor in fem.allEditors) {
                    val vFile = editor.file
                    if (vFile?.name == fileName) {
                        val psiFile = psiManager.findFile(vFile)
                        if (psiFile != null) {
                            val methodInfo = findMethodInFile(psiFile, lineNumber)
                            if (methodInfo != null) {
                                return@runReadAction methodInfo
                            }
                        }
                    }
                }

                // If not found in open editors, search project files
                val projectScope = GlobalSearchScope.projectScope(project)
                val virtualFiles = FilenameIndex.getVirtualFilesByName(fileName, projectScope)
                for (vFile in virtualFiles) {
                    val psiFile = psiManager.findFile(vFile)
                    if (psiFile != null) {
                        val methodInfo = findMethodInFile(psiFile, lineNumber)
                        if (methodInfo != null) {
                            return@runReadAction methodInfo
                        }
                    }
                }

                // Ultimate fallback
                "$fileName:$lineNumber"
            } catch (e: Exception) {
                println("PERFORATOR: Error resolving method reference: ${e.message}")
                e.printStackTrace()
                "$fileName:$lineNumber"
            }
        }
    }

    private fun findMethodInFile(psiFile: PsiFile, lineNumber: Int): String? {
        try {
            val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile) ?: return null

            if (lineNumber <= 0 || lineNumber > document.lineCount) {
                return null
            }

            val lineStartOffset = document.getLineStartOffset(lineNumber - 1)
            val lineEndOffset = if (lineNumber < document.lineCount) {
                document.getLineStartOffset(lineNumber) - 1
            } else {
                document.textLength
            }

            // Try multiple positions on the line to find a good element
            val offsets = listOf(
                lineStartOffset,
                lineStartOffset + 1,
                (lineStartOffset + lineEndOffset) / 2,
                lineEndOffset - 1
            ).filter { it >= lineStartOffset && it <= lineEndOffset && it < document.textLength }

            for (offset in offsets) {
                val element = psiFile.findElementAt(offset)
                if (element != null) {
                    val methodInfo = findContainingMethod(element)
                    if (methodInfo != null) {
                        println("PERFORATOR: Found method: $methodInfo for line $lineNumber in ${psiFile.name}")
                        return methodInfo
                    }
                }
            }

            println("PERFORATOR: No method found for line $lineNumber in ${psiFile.name}")
            return null
        } catch (e: Exception) {
            println("PERFORATOR: Error finding method in file: ${e.message}")
            return null
        }
    }

    private fun findContainingMethod(element: PsiElement?): String? {
        var current = element

        // Walk up the PSI tree to find the containing method
        while (current != null) {
            when {
                // Java method
                current is PsiMethod -> {
                    val className = current.containingClass?.qualifiedName
                    val methodName = current.name
                    val result = if (className != null) {
                        "${className.replace('.', '/')}.${methodName}"
                    } else {
                        methodName
                    }
                    println("PERFORATOR: Found Java method: $result")
                    return result
                }

                // Kotlin function - more comprehensive check
                isKotlinFunction(current) -> {
                    val methodName = extractKotlinMethodName(current)
                    val className = findKotlinContainingClass(current)

                    if (methodName != null) {
                        val result = if (className != null) {
                            "${className.replace('.', '/')}.${methodName}"
                        } else {
                            methodName
                        }
                        println("PERFORATOR: Found Kotlin method: $result")
                        return result
                    }
                }
            }
            current = current.parent
        }

        println("PERFORATOR: No containing method found")
        return null
    }

    private fun isKotlinFunction(element: PsiElement): Boolean {
        val className = element.javaClass.simpleName.lowercase()
        return className.contains("ktfunction") ||
                className.contains("ktnamedfunction") ||
                className.contains("kotlinfunction") ||
                className.contains("function") && className.contains("kt")
    }

    private fun extractKotlinMethodName(element: PsiElement): String? {
        return try {
            // Try reflection to get the name
            val nameMethod = element.javaClass.methods.find { it.name == "getName" }
            val name = nameMethod?.invoke(element) as? String
            if (name != null && name.isNotBlank()) {
                return name
            }

            // Try to find identifier child
            val children = element.children
            for (child in children) {
                val childClassName = child.javaClass.simpleName.lowercase()
                if (childClassName.contains("identifier") || childClassName.contains("name")) {
                    val text = child.text
                    if (text.isNotBlank() && text.matches(Regex("\\w+"))) {
                        return text
                    }
                }
            }

            // Last resort - parse from text
            val text = element.text?.lines()?.firstOrNull()?.trim()
            if (text != null) {
                // Pattern for Kotlin functions: "fun methodName(" or "private fun methodName("
                val kotlinPattern = Regex("""(?:public|private|protected|internal)?\s*(?:suspend\s+)?fun\s+(\w+)\s*\(""")
                val match = kotlinPattern.find(text)
                return match?.groupValues?.get(1)
            }

            null
        } catch (e: Exception) {
            println("PERFORATOR: Error extracting Kotlin method name: ${e.message}")
            null
        }
    }

    private fun findKotlinContainingClass(element: PsiElement): String? {
        var current = element.parent

        while (current != null) {
            val className = current.javaClass.simpleName.lowercase()

            when {
                current is PsiClass -> {
                    return current.qualifiedName
                }
                className.contains("ktclass") || className.contains("kotlinclass") -> {
                    // Try to get Kotlin class name using reflection
                    try {
                        val fqNameMethod = current.javaClass.methods.find { it.name == "getFqName" }
                        if (fqNameMethod != null) {
                            val fqName = fqNameMethod.invoke(current)
                            val asStringMethod = fqName?.javaClass?.methods?.find { it.name == "asString" }
                            val fqNameString = asStringMethod?.invoke(fqName) as? String
                            if (fqNameString != null) {
                                return fqNameString
                            }
                        }

                        // Fallback to name method
                        val nameMethod = current.javaClass.methods.find { it.name == "getName" }
                        val name = nameMethod?.invoke(current) as? String
                        if (name != null) {
                            // Try to get package from file
                            val containingFile = current.containingFile
                            val packageName = when (containingFile) {
                                is PsiJavaFile -> containingFile.packageName
                                else -> extractPackageFromKotlinFile(containingFile)
                            }
                            return if (packageName.isNotEmpty()) "$packageName.$name" else name
                        }
                    } catch (e: Exception) {
                        println("PERFORATOR: Error getting Kotlin class name: ${e.message}")
                    }
                }
            }
            current = current.parent
        }

        // Fallback - try to extract from file
        val containingFile = element.containingFile
        return when (containingFile) {
            is PsiJavaFile -> {
                val firstClass = containingFile.classes.firstOrNull()
                firstClass?.qualifiedName
            }
            else -> {
                val packageName = extractPackageFromKotlinFile(containingFile)
                val fileName = containingFile.name.substringBeforeLast('.')
                if (packageName.isNotEmpty()) "$packageName.$fileName" else fileName
            }
        }
    }

    private fun extractPackageFromKotlinFile(psiFile: PsiFile): String {
        return try {
            // Look for package directive
            val children = psiFile.children
            for (child in children) {
                val className = child.javaClass.simpleName.lowercase()
                if (className.contains("package")) {
                    val text = child.text
                    val packagePattern = Regex("""package\s+([\w.]+)""")
                    val match = packagePattern.find(text)
                    val packageName = match?.groupValues?.get(1)
                    if (packageName != null) {
                        return packageName
                    }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun buildOptimizationPrompt(
        profileType: String,
        methodReference: String,
        serviceName: String,
        timePeriod: String
    ): String {
        val (startTime, endTime) = calculateTimeRange(timePeriod)
        return "Suggest $profileType optimization for the function $methodReference.\n" +
                "Use performance data from Pyroscope for the service \"$serviceName\". " +
                "fetch_pyroscope_profile with start_rfc_3339 = $startTime and end_rfc_3339 = $endTime."
    }

    private fun calculateTimeRange(timePeriod: String): Pair<String, String> {
        val now = Instant.now()
        val endTime = now.toString() // RFC 3339 format

        val startTime = when (timePeriod) {
            "now-15m" -> now.minus(15, ChronoUnit.MINUTES)
            "now-30m" -> now.minus(30, ChronoUnit.MINUTES)
            "now-1h" -> now.minus(1, ChronoUnit.HOURS)
            "now-1d" -> now.minus(1, ChronoUnit.DAYS)
            "now-3d" -> now.minus(3, ChronoUnit.DAYS)
            "now-1w" -> now.minus(7, ChronoUnit.DAYS)
            else -> now.minus(15, ChronoUnit.MINUTES) // Default fallback
        }.toString() // RFC 3339 format

        return startTime to endTime
    }

    override fun equals(other: Any?): Boolean =
        other is BadgeOnlyGutterRenderer &&
                other.text == text &&
                other.hot == hot &&
                other.alignRight == alignRight

    override fun hashCode(): Int =
        (text.hashCode() * 31 + if (hot) 1 else 0) * 31 + if (alignRight) 1 else 0

    override fun getAlignment(): Alignment =
        if (alignRight) Alignment.RIGHT else Alignment.LEFT

    private fun ensureSizeMeasured() {
        if (cachedWidth >= 0 && cachedHeight >= 0) return

        val g2 = dummyGraphics()
        val fm = g2.fontMetrics
        val textW = fm.stringWidth(text)
        val textH = fm.ascent

        cachedWidth = textW + padX * 2
        cachedHeight = textH + padY * 2 + 4

        g2.dispose()
    }

    override fun getIcon(): Icon = object : Icon {
        override fun getIconWidth(): Int { ensureSizeMeasured(); return cachedWidth }
        override fun getIconHeight(): Int { ensureSizeMeasured(); return cachedHeight }

        override fun paintIcon(c: java.awt.Component, g: java.awt.Graphics, x: Int, y: Int) {
            ensureSizeMeasured()
            val g2 = g as Graphics2D
            val oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
            g2.font = scheme.getFont(EditorFontType.PLAIN)
            val fm = g2.fontMetrics

            val pillW = cachedWidth
            val pillH = cachedHeight - 4
            val textH = fm.ascent

            val bg = if (hot) JBColor(0xD84343, 0xB71C1C) else JBColor(0x4A4D51, 0x3A3D41)
            val fg = JBColor(0xFFFFFF, 0xFFFFFF)

            val rightEdge = x + pillW
            val dx = rightEdge - pillW
            val dy = y + 2

            g2.color = bg
            g2.fillRoundRect(dx, dy, pillW, pillH, radius.toInt(), radius.toInt())

            g2.color = fg
            val tx = dx + padX
            val ty = dy + padY + textH - 1
            g2.drawString(text, tx, ty)

            if (oldAA != null) {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA)
            }
        }
    }

    private fun dummyGraphics(): Graphics2D {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        val scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
        g2.font = scheme.getFont(EditorFontType.PLAIN)
        return g2
    }
}
