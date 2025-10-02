package com.perforator.perforator

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.google.perftools.profiles.ProfileProto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.Icon

// ========================================
// ACTIONS
// ========================================

/**
 * Action to fetch profiling data from Pyroscope server
 * Triggered from Tools menu or toolbar
 */
class FetchFromPyroscopeAction : AnAction("Fetch from Pyroscope"), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = project.service<PerforatorSettingsService>()
        val service = project.service<PerforatorService>()

        // Execute fetch on background thread
        AppExecutorUtil.getAppExecutorService().submit {
            val query = settings.buildPyroscopeQuery()
            val success = service.loadFromPyroscope(
                project,
                settings.baseUrl,
                query,
                settings.timeWindow
            )

            // Update UI on main thread
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    settings.setDataSourcePyroscope()
                    PerforatorToolbar.showInAllEditors(project)
                    service.clearAllGutters(project)
                    service.triggerRefresh(project)
                } else {
                    Messages.showErrorDialog(
                        project,
                        "Failed to fetch from Pyroscope. Check settings and ensure proper authentication.",
                        "Perforator Error"
                    )
                }
            }
        }
    }
}

/**
 * Action to load profiling data from local .pb.gz or .pprof file
 * Supports Google Cloud Profiler format
 */
class LoadProfileFileAction : AnAction("Load Profile File (.pb.gz)"), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Configure file chooser for profile files
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select Profile File")
            .withDescription("Choose a .pb.gz profile file downloaded from Google Cloud Profiler")
            .withFileFilter { virtualFile ->
                virtualFile.name.endsWith(".pb.gz") || virtualFile.name.endsWith(".pprof")
            }

        val selectedFile = FileChooser.chooseFile(descriptor, project, null)
        if (selectedFile != null) {
            // Process file on background thread
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    // Read and optionally decompress file
                    val fileBytes = selectedFile.contentsToByteArray()
                    val decompressedBytes = if (selectedFile.name.endsWith(".gz")) {
                        decompressGzip(fileBytes)
                    } else {
                        fileBytes
                    }

                    // Parse profile data
                    val reader = ProfileFileReader(project)
                    val profilingData = reader.parseProfileBytes(decompressedBytes)

                    // Update UI on main thread
                    ApplicationManager.getApplication().invokeLater {
                        val service = project.service<PerforatorService>()
                        val settings = project.service<PerforatorSettingsService>()

                        service.clearAllGutters(project)
                        service.clearData()
                        service.setProfilingData(profilingData)

                        // Mark data source as FILE
                        settings.setDataSourceFile()

                        // Show toolbars and refresh
                        PerforatorToolbar.showInAllEditors(project)
                        PerforatorToolbar.syncAllToolbars(project)
                        DaemonCodeAnalyzer.getInstance(project).restart()
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        val errorMsg = "Failed to load profile file: ${e.message}"
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Perforator Notifications")
                            .createNotification(errorMsg, NotificationType.ERROR)
                            .notify(project)
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}

// ========================================
// GUTTER ANNOTATOR
// ========================================

/**
 * Annotator that adds performance badges to code editor gutters
 * Processes method declarations and adds visual indicators for hot spots
 */
class PerforatorGutterAnnotator : Annotator, DumbAware {

    companion object {
        // Key to track which lines already have badges in current analysis session
        private val EMITTED_LINES_KEY = Key.create<MutableSet<Int>>("perforator.emitted.lines")
    }

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile ?: return
        val project = file.project
        val settings = project.service<PerforatorSettingsService>()

        // Only show badges when toolbar is visible
        if (!settings.isVisible) return

        val doc: Document = element.containingFile.viewProvider.document ?: return
        val fileName = file.virtualFile?.name ?: return
        val data = project.service<PerforatorService>().getProfilingData(fileName) ?: return

        // Extract method information from PSI element
        val methodInfo = extractMethodInfo(element) ?: return

        // Find profiling data matching this method
        val matchingProfilingData = findMatchingProfilingData(data, methodInfo)
        if (matchingProfilingData.isEmpty()) return

        // Calculate line position for badge
        val elementLine = doc.getLineNumber(element.textOffset) + 1
        val lineStart = doc.getLineStartOffset(doc.getLineNumber(element.textOffset))

        // Aggregate all profiling data for this method
        val aggregatedData = aggregateProfilingData(matchingProfilingData)

        // Track emitted lines to avoid duplicates
        val session = holder.currentAnnotationSession
        val emitted = session.getUserData(EMITTED_LINES_KEY) ?: mutableSetOf<Int>().also {
            session.putUserData(EMITTED_LINES_KEY, it)
        }

        if (!emitted.add(lineStart)) {
            return // Already processed this line
        }

        // Format display value and determine if hot
        val (label, isHot) = formatProfilingValue(settings, aggregatedData)

        // Create annotation
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(TextRange(element.textOffset, element.textOffset))
            .create()

        // Add gutter badge to all editors showing this file
        ApplicationManager.getApplication().invokeLater {
            val methodName = methodInfo.methodName.ifBlank {
                aggregatedData.methodName.takeIf { it.isNotBlank() } ?: "unknown"
            }
            addGutterBadge(project, fileName, doc, lineStart, label, isHot, elementLine, methodName)
        }
    }

    /**
     * Formats profiling value based on profile type (CPU vs Memory)
     * @return Pair of (formatted label, isHot flag)
     */
    private fun formatProfilingValue(
        settings: PerforatorSettingsService,
        data: ProfilingData
    ): Pair<String, Boolean> {
        return when (settings.profileType) {
            "Memory" -> {
                val bytes = data.executionTime
                val mb = bytes / (1024.0 * 1024.0)
                val formattedMb = when {
                    mb >= 10 -> String.format("%.0f MB", mb)
                    mb >= 1 -> String.format("%.1f MB", mb)
                    else -> String.format("%.2f MB", mb)
                }
                val hot = mb >= settings.hotThresholdMemory
                formattedMb to hot
            }
            else -> { // CPU and Wall time
                val nanoseconds = data.executionTime
                val ms = nanoseconds / 1_000_000.0
                val formattedMs = when {
                    ms >= 1000 -> String.format("%.0f ms", ms)
                    ms >= 100 -> String.format("%.1f ms", ms)
                    ms >= 10 -> String.format("%.2f ms", ms)
                    ms >= 1 -> String.format("%.2f ms", ms)
                    else -> String.format("%.3f ms", ms)
                }
                val hot = ms >= settings.hotThresholdCpu
                formattedMs to hot
            }
        }
    }

    /**
     * Data class holding method information
     */
    private data class MethodInfo(
        val methodName: String,
        val className: String? = null,
        val parameters: String? = null
    ) {
        /**
         * Checks if this method info matches a profiling method name
         */
        fun matches(profilingMethodName: String): Boolean {
            return when {
                profilingMethodName == methodName -> true
                profilingMethodName.contains(".") &&
                        profilingMethodName.substringAfterLast(".") == methodName -> true
                profilingMethodName.contains("(") &&
                        profilingMethodName.substringBefore("(").substringAfterLast(".") == methodName -> true
                (methodName == "<init>" || methodName == "constructor") &&
                        (profilingMethodName.contains("<init>") || profilingMethodName.contains("constructor")) -> true
                else -> false
            }
        }
    }

    /**
     * Extracts method information from PSI element
     */
    private fun extractMethodInfo(element: PsiElement): MethodInfo? {
        return when (element) {
            // Java method
            is PsiMethod -> {
                MethodInfo(
                    methodName = element.name,
                    className = element.containingClass?.name,
                    parameters = element.parameterList.parameters.joinToString(",") { it.type.presentableText }
                )
            }
            // Java method identifier
            is PsiIdentifier -> {
                val parent = element.parent
                if (parent is PsiMethod) {
                    MethodInfo(
                        methodName = parent.name,
                        className = parent.containingClass?.name,
                        parameters = parent.parameterList.parameters.joinToString(",") { it.type.presentableText }
                    )
                } else null
            }
            // Kotlin function
            is PsiNamedElement -> {
                val elementStr = element.toString()
                when {
                    elementStr.contains("KtNamedFunction") ||
                            elementStr.contains("KtPrimaryConstructor") ||
                            elementStr.contains("KtSecondaryConstructor") -> {
                        MethodInfo(
                            methodName = element.name ?: "unknown",
                            className = null
                        )
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Finds all profiling data entries matching the given method
     */
    private fun findMatchingProfilingData(
        data: Map<Int, ProfilingData>,
        methodInfo: MethodInfo
    ): List<ProfilingData> {
        return data.values.filter { profilingData ->
            profilingData.methodName.isNotBlank() && methodInfo.matches(profilingData.methodName)
        }
    }

    /**
     * Aggregates multiple profiling data entries into one
     */
    private fun aggregateProfilingData(profilingDataList: List<ProfilingData>): ProfilingData {
        if (profilingDataList.isEmpty()) {
            return ProfilingData(0, 0.0, 0)
        }

        val firstData = profilingDataList.first()
        val totalExecutionTime = profilingDataList.sumOf { it.executionTime }
        val totalSamples = profilingDataList.sumOf { it.samples }

        return firstData.copy(
            executionTime = totalExecutionTime,
            samples = totalSamples
        )
    }

    /**
     * Adds gutter badge to all editors displaying the file
     */
    private fun addGutterBadge(
        project: Project,
        fileName: String,
        doc: Document,
        lineStart: Int,
        label: String,
        isHot: Boolean,
        line: Int,
        methodName: String
    ) {
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

            try {
                // Add new range highlighter
                val rh: RangeHighlighter = markup.addRangeHighlighter(
                    lineStart,
                    lineStart,
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    null,
                    HighlighterTargetArea.LINES_IN_RANGE
                )

                // Attach gutter renderer
                rh.gutterIconRenderer = BadgeOnlyGutterRenderer(
                    label,
                    isHot,
                    alignRight = true,
                    project = project,
                    fileName = fileName,
                    lineNumber = line,
                    methodName = methodName
                )
            } catch (e: Exception) {
                println("PERFORATOR ANNOTATOR: Error adding badge to editor for line $line: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

// ========================================
// GUTTER RENDERER
// ========================================

/**
 * Custom gutter icon renderer for performance badges
 * Displays execution time/memory usage with color coding for hot spots
 * Right-click copies AI optimization prompt to clipboard
 */
internal class BadgeOnlyGutterRenderer(
    private val text: String,
    private val hot: Boolean,
    private val alignRight: Boolean,
    private val project: Project,
    private val fileName: String,
    private val lineNumber: Int,
    private val methodName: String = "unknown"
) : GutterIconRenderer() {

    private val padX = 10
    private val padY = 5
    private val radius = 10f

    @Volatile private var cachedWidth: Int = -1
    @Volatile private var cachedHeight: Int = -1

    override fun getTooltipText(): String? = text

    override fun isNavigateAction(): Boolean = false

    override fun getClickAction(): AnAction? = null

    /**
     * Right-click action copies AI optimization prompt to clipboard
     */
    override fun getRightButtonClickAction(): AnAction? {
        return object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val settings = project.service<PerforatorSettingsService>()
                val profileType = if (settings.profileType == "Memory") "Memory usage" else "CPU time"

                // Create method reference from class and method name
                val className = fileName.substringBefore(".") // Remove extension
                val methodReference = if (methodName != "unknown" && methodName.isNotBlank()) {
                    "$className.$methodName" // e.g., "MyClass.myMethod"
                } else {
                    "$fileName:$lineNumber" // Fallback to line-based reference
                }

                // Generate different prompts based on data source
                val prompt = when (settings.dataSource) {
                    "PYROSCOPE" -> {
                        val serviceName = settings.serviceName
                        "Suggest $profileType optimization for the method $methodReference in service '$serviceName'."
                    }
                    "FILE" -> {
                        "Suggest $profileType optimization for the method $methodReference."
                    }
                    else -> {
                        "Suggest $profileType optimization for the method $methodReference."
                    }
                }

                try {
                    // Copy to clipboard
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    val stringSelection = StringSelection(prompt)
                    clipboard.setContents(stringSelection, null)

                    // Show notification
                    ApplicationManager.getApplication().invokeLater {
                        com.intellij.notification.Notifications.Bus.notify(
                            com.intellij.notification.Notification(
                                "Perforator",
                                "AI Prompt Copied",
                                "Optimization prompt copied to clipboard",
                                com.intellij.notification.NotificationType.INFORMATION
                            ),
                            project
                        )
                    }
                } catch (ex: Exception) {
                    println("PERFORATOR: Error copying to clipboard: ${ex.message}")
                }
            }
        }
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

    /**
     * Pre-calculates badge dimensions using dummy graphics context
     */
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
        override fun getIconWidth(): Int {
            ensureSizeMeasured()
            return cachedWidth
        }

        override fun getIconHeight(): Int {
            ensureSizeMeasured()
            return cachedHeight
        }

        /**
         * Paints rounded badge with text
         */
        override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
            ensureSizeMeasured()
            val g2 = g as Graphics2D
            val oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Get editor font and colors
            val scheme = EditorColorsManager.getInstance().globalScheme
            g2.font = scheme.getFont(EditorFontType.PLAIN)
            val fm = g2.fontMetrics

            val pillW = cachedWidth
            val pillH = cachedHeight - 4
            val textH = fm.ascent

            // Color scheme
            val bg = if (hot) JBColor(0xD84343, 0xB71C1C) else JBColor(0x4A4D51, 0x3A3D41)
            val fg = JBColor(0xFFFFFF, 0xFFFFFF)

            // Draw rounded rectangle badge
            val rightEdge = x + pillW
            val dx = rightEdge - pillW
            val dy = y + 2

            g2.color = bg
            g2.fillRoundRect(dx, dy, pillW, pillH, radius.toInt(), radius.toInt())

            // Draw text
            g2.color = fg
            val tx = dx + padX
            val ty = dy + padY + textH - 1
            g2.drawString(text, tx, ty)

            // Restore antialiasing
            if (oldAA != null) {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA)
            }
        }
    }

    /**
     * Creates dummy graphics context for size measurements
     */
    private fun dummyGraphics(): Graphics2D {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        val scheme = EditorColorsManager.getInstance().globalScheme
        g2.font = scheme.getFont(EditorFontType.PLAIN)
        return g2
    }
}

// ========================================
// SYMBOL RESOLVER
// ========================================

/**
 * Resolves Java/Kotlin symbols to file locations
 * Uses PSI to find method declarations and their line numbers
 */
private class SymbolResolver(private val project: Project) {

    private val cache = HashMap<String, Pair<String, Int>?>()
    private val searchScope = GlobalSearchScope.allScope(project)

    /**
     * Resolves a symbol string to (filename, line number)
     * @param symbol Format: "com.example.MyClass.myMethod" or "com/example/MyClass.myMethod"
     * @return Pair of filename and line number, or null if not found
     */
    fun resolve(symbol: String): Pair<String, Int>? {
        return cache.getOrPut(symbol) {
            ApplicationManager.getApplication().runReadAction<Pair<String, Int>?> {
                val candidate = extractFqnAndMethod(symbol) ?: return@runReadAction null
                val (classFqn, methodName) = candidate

                // Find class using JavaPsiFacade
                val javaFacade = JavaPsiFacade.getInstance(project)
                val psiClass = javaFacade.findClass(classFqn, searchScope)

                if (psiClass != null) {
                    // Find method by name
                    val methods: Array<PsiMethod> = psiClass.findMethodsByName(methodName, true)
                    val m: PsiMethod? = methods.firstOrNull()

                    if (m != null) {
                        return@runReadAction methodLocation(m)
                    }
                }

                null
            }
        }
    }

    /**
     * Extracts file location from PsiMethod
     */
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

    /**
     * Extracts fully qualified class name and method name from symbol
     * @param symbol Format: "com.example.MyClass.myMethod(String,int)"
     * @return Pair of (classFqn, methodName) or null
     */
    private fun extractFqnAndMethod(symbol: String): Pair<String, String>? {
        val s = symbol.trim().replace('/', '.')
        val base = s.substringBefore("(")
        val lastDot = base.lastIndexOf('.')
        if (lastDot <= 0) return null

        val classPart = base.substring(0, lastDot)
        val methodPart = base.substring(lastDot + 1).takeWhile { ch ->
            ch != '(' && ch.isLetterOrDigit()
        }

        if (classPart.isEmpty() || methodPart.isEmpty()) return null
        return classPart to methodPart
    }
}

// ========================================
// JSON FLAMEBEARER READER (Pyroscope Format)
// ========================================

/**
 * Data structures for Pyroscope Flamebearer format
 */
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
    val metadata: Map<String, JsonElement>? = null
)

/**
 * Parses Pyroscope JSON flamebearer format into profiling data
 * Supports file:line format and symbol resolution
 */
class JsonFlamebearerReader(private val project: Project) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val fileLineRegex = Regex("""([^/:]+\.(java|kt|scala)):(\d+)""")
    private val resolver = SymbolResolver(project)

    /**
     * Reads from file
     */
    fun read(file: File): Map<String, Map<Int, ProfilingData>> = readText(file.readText())

    /**
     * Parses JSON text into profiling data map
     * @return Map of filename -> (line number -> ProfilingData)
     */
    fun readText(text: String): Map<String, Map<Int, ProfilingData>> {
        val env = parseEnvelopeKotlinx(text)
        return aggregate(env.flamebearer)
    }

    /**
     * Extracts method name from symbol string
     */
    private fun extractMethodName(symbol: String): String {
        val cleanSymbol = symbol.trim()

        // Handle class.method format
        val lastDot = cleanSymbol.lastIndexOf('.')
        if (lastDot > 0 && lastDot < cleanSymbol.length - 1) {
            val methodPart = cleanSymbol.substring(lastDot + 1)
            val parenIndex = methodPart.indexOf('(')
            return if (parenIndex > 0) methodPart.substring(0, parenIndex) else methodPart
        }

        // Handle package/Class format
        val lastSlash = cleanSymbol.lastIndexOf('/')
        if (lastSlash > 0) {
            return cleanSymbol.substring(lastSlash + 1)
        }

        // Remove parameters from method signatures
        val parenIndex = cleanSymbol.indexOf('(')
        val baseMethod = if (parenIndex > 0) cleanSymbol.substring(0, parenIndex) else cleanSymbol

        return if (baseMethod.isNotBlank()) baseMethod else "unknown"
    }

    /**
     * Aggregates flamebearer levels into per-file profiling data
     */
    private fun aggregate(fb: FlamebearerV1): Map<String, Map<Int, ProfilingData>> {
        val names = fb.names
        val levels: List<List<Long>> = fb.levels
        val byFile = mutableMapOf<String, MutableMap<Int, ProfilingData>>()

        val chunkSize = 4
        var totalNodes = 0
        var matched = 0
        val samplePrinted = HashSet<String>()

        // Process each level of the flame graph
        for (row: List<Long> in levels) {
            var i = 0
            val size = row.size

            while (i + chunkSize <= size) {
                totalNodes++
                val self: Long = row[i + 2]
                val nameIdxLong: Long = row[i + 3]
                val nameIdx = nameIdxLong.toInt()
                val symbol = names.getOrNull(nameIdx) ?: ""

                // Try to parse file and line from symbol
                var loc: Pair<String, Int>? = parseFileAndLine(symbol)

                // Check parentheses for embedded file:line info
                if (loc == null) {
                    val i1 = symbol.lastIndexOf("(")
                    val i2 = symbol.lastIndexOf(")")
                    if (i1 > 0 && i2 > i1) {
                        val inside = symbol.substring(i1 + 1, i2)
                        loc = parseFileAndLine(inside)
                    }
                }

                // Try PSI symbol resolution
                if (loc == null) {
                    loc = resolver.resolve(symbol)
                }

                if (loc != null) {
                    matched++
                    val (baseFile, line) = loc

                    if (samplePrinted.size < 5) {
                        samplePrinted.add("$baseFile:$line")
                    }

                    val lineMap = byFile.getOrPut(baseFile) { mutableMapOf() }
                    val prev = lineMap[line]
                    val add: Double = if (self > 0.0) self.toDouble() else 0.0
                    val methodName = extractMethodName(symbol)

                    lineMap[line] = if (prev == null) {
                        ProfilingData(line, add, 1, methodName)
                    } else {
                        prev.copy(
                            executionTime = prev.executionTime + add,
                            samples = prev.samples + 1,
                            methodName = if (prev.methodName.isBlank()) methodName else prev.methodName
                        )
                    }
                }

                i += chunkSize
            }
        }

        return byFile.mapValues { it.value as Map<Int, ProfilingData> }
    }

    /**
     * Parses filename and line number from symbol string
     * Matches patterns like "MyFile.java:123"
     */
    private fun parseFileAndLine(symbol: String): Pair<String, Int>? {
        fileLineRegex.find(symbol)?.let { m ->
            val file = m.groupValues[1]
            val line = m.groupValues[3].toIntOrNull() ?: return null
            return file to line
        }
        return null
    }

    /**
     * Parses JSON with fallback for different formats
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun parseEnvelopeKotlinx(text: String): FlamebearerEnvelope {
        return try {
            json.decodeFromString(text)
        } catch (t: Throwable) {
            // Manual parsing fallback
            val root = json.parseToJsonElement(text)
            val obj = root as? JsonObject ?: throw IllegalArgumentException("Invalid JSON: expected object")
            val fbElem: JsonElement = obj["flamebearer"] ?: root
            val fbObj: JsonObject = fbElem.jsonObject

            val names: List<String> = fbObj["names"]?.jsonArray?.let { arr ->
                buildList(arr.size) {
                    for (je in arr) {
                        add(je.jsonPrimitive.content)
                    }
                }
            } ?: emptyList()

            val levels: List<List<Long>> = fbObj["levels"]?.jsonArray?.let { rows ->
                buildList<List<Long>>(rows.size) {
                    for (rowElem in rows) {
                        val rowArr: JsonArray = rowElem.jsonArray
                        val longs = ArrayList<Long>(rowArr.size)
                        for (v in rowArr) {
                            longs.add(v.jsonPrimitive.long)
                        }
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

// ========================================
// PROFILE FILE READER (Google Cloud Profiler Format)
// ========================================

/**
 * Parses .pb.gz profile files in protobuf format
 * Supports Google Cloud Profiler pprof format
 */
class ProfileFileReader(private val project: Project) {

    /**
     * Parses protobuf profile bytes into profiling data
     */
    fun parseProfileBytes(profileBytes: ByteArray): Map<String, Map<Int, ProfilingData>> {
        try {
            val profile = ProfileProto.Profile.parseFrom(profileBytes)

            // Build lookup maps
            val stringTable = profile.stringTableList
            val getString = { index: Long ->
                if (index >= 0 && index < stringTable.size) stringTable[index.toInt()] else ""
            }

            val functionsById = profile.functionList.associateBy { it.id }
            val locationsById = profile.locationList.associateBy { it.id }

            // Detect profile type and value index
            val (detectedProfileType, valueIndex) = detectProfileTypeAndIndex(
                profile.sampleTypeList,
                getString
            )

            // Update project settings to match detected type
            val settings = project.service<PerforatorSettingsService>()
            settings.profileType = detectedProfileType

            val byFile = mutableMapOf<String, MutableMap<Int, ProfilingData>>()
            parseSamplesFromFile(profile, byFile, functionsById, locationsById, valueIndex, getString)

            return byFile.mapValues { it.value.toMap() }
        } catch (e: Exception) {
            println("PERFORATOR: Error parsing profile: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Detects profile type (CPU vs Memory) from sample types
     * @return Pair of (profileType, valueIndex) where valueIndex points to the correct value
     */
    private fun detectProfileTypeAndIndex(
        sampleTypes: List<ProfileProto.ValueType>,
        getString: (Long) -> String
    ): Pair<String, Int> {
        if (sampleTypes.isEmpty()) return "CPU" to 0

        // Check for heap profiles (alloc_space, inuse_space, etc.)
        for ((index, sampleType) in sampleTypes.withIndex()) {
            val typeName = getString(sampleType.type).lowercase()
            val unitName = getString(sampleType.unit).lowercase()

            // Match any "space" type with "bytes" unit
            // Covers: alloc_space, inuse_space, heap_space
            // Excludes: alloc_objects, inuse_objects (count, not bytes)
            if (typeName.contains("space") && unitName.contains("bytes")) {
                return "Memory" to index
            }
        }

        // Check for CPU profiles
        for ((index, sampleType) in sampleTypes.withIndex()) {
            val typeName = getString(sampleType.type).lowercase()
            val unitName = getString(sampleType.unit).lowercase()

            if (typeName.contains("cpu") ||
                unitName.contains("nanoseconds") ||
                unitName.contains("ns")) {
                return "CPU" to index
            }
        }

        // Fallback: check for any type with bytes unit
        for ((index, sampleType) in sampleTypes.withIndex()) {
            val unitName = getString(sampleType.unit).lowercase()
            if (unitName.contains("bytes")) {
                return "Memory" to index
            }
        }

        return "CPU" to 0
    }

    /**
     * Parses all samples and aggregates by file and line
     */
    private fun parseSamplesFromFile(
        profile: ProfileProto.Profile,
        byFile: MutableMap<String, MutableMap<Int, ProfilingData>>,
        functionsById: Map<Long, ProfileProto.Function>,
        locationsById: Map<Long, ProfileProto.Location>,
        valueIndex: Int,
        getString: (Long) -> String
    ) {
        val processedMethods = mutableSetOf<String>()

        for (sample in profile.sampleList) {
            val sampleValues = sample.valueList
            if (sampleValues.isEmpty()) continue

            val measurementValue: Double = if (valueIndex >= 0 && valueIndex < sampleValues.size) {
                sampleValues[valueIndex].toDouble()
            } else {
                sampleValues[0].toDouble()
            }

            if (measurementValue <= 0.0) continue

            processedMethods.clear()
            val locationIds = sample.locationIdList.map { it.toLong() }
            if (locationIds.isEmpty()) continue

            // Process all methods in call stack (cumulative attribution)
            for (locationId in locationIds) {
                val location = locationsById[locationId] ?: continue

                for (line in location.lineList) {
                    val fn = functionsById[line.functionId] ?: continue
                    val filePath = getString(fn.filename)
                    val functionName = getString(fn.name)
                    val lineNumber = line.line.toInt()

                    if (filePath.isBlank() || functionName.isBlank()) continue

                    val baseName = extractJvmFileName(filePath)
                    if (baseName.isBlank()) continue

                    if (!isLikelyProjectFile(filePath, functionName)) continue

                    var finalLineNumber = lineNumber
                    if (lineNumber <= 0) continue

                    val methodKey = "$baseName:$finalLineNumber:$functionName"
                    if (!processedMethods.contains(methodKey)) {
                        processedMethods.add(methodKey)
                        addToFileMap(byFile, baseName, finalLineNumber, measurementValue, functionName)
                    }
                }
            }
        }
    }

    /**
     * Extracts just the filename from a full path
     */
    private fun extractJvmFileName(filePath: String): String {
        return when {
            filePath.contains("/") -> File(filePath).name
            filePath.contains("\\") -> File(filePath.replace("\\", "/")).name
            filePath.endsWith(".java") || filePath.endsWith(".kt") || filePath.endsWith(".scala") -> filePath
            else -> if (filePath.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) "$filePath.java" else filePath
        }
    }

    /**
     * Filters out system/framework files to focus on project code
     */
    private fun isLikelyProjectFile(filePath: String, functionName: String): Boolean {
        val systemPrefixes = listOf(
            "java/lang/", "java/util/", "java/io/", "java/net/", "java/nio/",
            "sun/", "com/sun/", "jdk/", "javax/", "kotlin/", "scala/",
            "org/springframework/", "org/apache/", "com/google/", "io/netty/"
        )

        return !systemPrefixes.any { filePath.contains(it) } &&
                !functionName.contains("[unknown]") &&
                !functionName.contains("Interpreter") &&
                !functionName.startsWith("java.") &&
                !functionName.startsWith("sun.")
    }

    /**
     * Adds or updates profiling data for a specific file line
     */
    private fun addToFileMap(
        byFile: MutableMap<String, MutableMap<Int, ProfilingData>>,
        fileName: String,
        line: Int,
        value: Double,
        methodName: String
    ) {
        val lineMap = byFile.getOrPut(fileName) { mutableMapOf() }
        val prev = lineMap[line]

        lineMap[line] = if (prev == null) {
            ProfilingData(line, value, 1, methodName)
        } else {
            prev.copy(
                executionTime = prev.executionTime + value,
                samples = prev.samples + 1,
                methodName = if (prev.methodName.isBlank()) methodName else prev.methodName
            )
        }
    }
}