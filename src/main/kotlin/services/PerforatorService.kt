package com.perforator.perforator

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.util.ui.UIUtil
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.Timer

/**
 * Core service managing profiling data lifecycle
 * Handles loading, storing, and refreshing profiling information
 */
@Service
@Suppress("UnstableApiUsage")
class PerforatorService {

    // Thread-safe storage: filename -> line -> ProfilingData
    private val profilingData = ConcurrentHashMap<String, Map<Int, ProfilingData>>()

    // Version counter for cache invalidation
    private val version = AtomicLong(0L)

    /**
     * Loads profiling data from Pyroscope server
     * @return true if successful, false otherwise
     */
    fun loadFromPyroscope(
        project: Project,
        baseUrl: String,
        query: String,
        fromWindow: String
    ): Boolean {
        return try {
            val settings = project.service<PerforatorSettingsService>()

            // Build and execute request
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
            val fullUrl = "$baseUrl/pyroscope/render?query=$encodedQuery&from=$fromWindow"

            val jsonText = HttpRequestHelper.makePyroscopeRequest(
                fullUrl,
                settings.hasBasicAuth,
                settings.basicAuthUsername,
                settings.basicAuthPassword
            ) ?: return false

            // Parse response and update data
            val reader = JsonFlamebearerReader(project)
            val byFile = reader.readText(jsonText)

            profilingData.clear()
            profilingData.putAll(byFile)
            version.incrementAndGet()

            true
        } catch (t: Throwable) {
            println("PERFORATOR DEBUG: Exception in loadFromPyroscope: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    /**
     * Gets profiling data for a specific file
     * @param filename Source file name
     * @return Map of line numbers to profiling data, or null if not available
     */
    fun getProfilingData(filename: String): Map<Int, ProfilingData>? = profilingData[filename]

    /**
     * Checks if any profiling data is currently loaded
     */
    fun hasData(): Boolean = profilingData.isNotEmpty()

    /**
     * Replaces all profiling data with new dataset
     */
    fun setProfilingData(data: Map<String, Map<Int, ProfilingData>>) {
        profilingData.clear()
        profilingData.putAll(data)
        version.incrementAndGet()
    }

    /**
     * Clears all profiling data
     */
    fun clearData() {
        profilingData.clear()
        version.incrementAndGet()
    }

    /**
     * Removes all gutter badges from all open editors
     */
    fun clearAllGutters(project: Project) {
        UIUtil.invokeLaterIfNeeded {
            val fem = FileEditorManager.getInstance(project)

            for (editor in fem.allEditors) {
                if (editor is TextEditor) {
                    val markup = editor.editor.markupModel

                    // Find and remove Perforator highlighters
                    val perforatorHighlighters = markup.allHighlighters.filter {
                        it.gutterIconRenderer is BadgeOnlyGutterRenderer
                    }

                    perforatorHighlighters.forEach { highlighter ->
                        try {
                            highlighter.dispose()
                        } catch (e: Exception) {
                            // Ignore disposal errors
                        }
                    }
                }
            }

            // Force visual refresh after clearing
            Timer(50) {
                fem.allEditors.forEach { fe ->
                    (fe as? TextEditor)?.editor?.contentComponent?.repaint()
                }
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    /**
     * Triggers IDE analysis refresh to update gutter badges
     */
    fun triggerRefresh(project: Project) {
        UIUtil.invokeLaterIfNeeded {
            val analyzer = DaemonCodeAnalyzer.getInstance(project)
            val fem = FileEditorManager.getInstance(project)
            val psiManager = PsiManager.getInstance(project)

            ApplicationManager.getApplication().runReadAction {
                var restartedAny = false

                // Restart analysis for all open files
                for (fe: FileEditor in fem.allEditors) {
                    val vFile = fe.file ?: continue
                    val psi = psiManager.findFile(vFile) ?: continue
                    analyzer.restart(psi)
                    restartedAny = true
                }

                // Fallback: restart all analysis
                if (!restartedAny) {
                    analyzer.restart()
                }
            }

            // Force UI refresh
            fem.allEditors.forEach { fe ->
                (fe as? TextEditor)?.editor?.contentComponent?.let { c ->
                    c.revalidate()
                    c.repaint()
                }
            }
        }
    }
}