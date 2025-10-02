package com.perforator.perforator

import com.intellij.openapi.components.*

/**
 * Persistent settings service for Perforator plugin
 * Stores configuration for Pyroscope connection and profiling preferences
 */
@Service
@State(
    name = "PerforatorSettings",
    storages = [Storage("perforator.xml")]
)
class PerforatorSettingsService : PersistentStateComponent<PerforatorSettingsService.State> {

    /**
     * Serializable state object holding all plugin settings
     */
    data class State(
        // Pyroscope connection settings
        var baseUrl: String = "http://localhost:4040",
        var basicAuthUsername: String = "",
        var basicAuthPassword: String = "",

        // Profiling configuration
        var profileType: String = "CPU",
        var serviceName: String = "my_application_name",
        var timeWindow: String = "now-15m",

        // Threshold settings (Double for precision)
        var hotThresholdCpu: Double = 50.0,
        var hotThresholdMemory: Double = 10.0,

        // UI state
        var isVisible: Boolean = false,
        var dataSource: String = "UNKNOWN"
    )

    private var myState = State()

    // Property accessors with delegation to state
    var baseUrl: String
        get() = myState.baseUrl
        set(value) { myState.baseUrl = value }

    var basicAuthUsername: String
        get() = myState.basicAuthUsername
        set(value) { myState.basicAuthUsername = value }

    var basicAuthPassword: String
        get() = myState.basicAuthPassword
        set(value) { myState.basicAuthPassword = value }

    var profileType: String
        get() = myState.profileType
        set(value) { myState.profileType = value }

    var serviceName: String
        get() = myState.serviceName
        set(value) { myState.serviceName = value }

    var timeWindow: String
        get() = myState.timeWindow
        set(value) { myState.timeWindow = value }

    var hotThresholdCpu: Double
        get() = myState.hotThresholdCpu
        set(value) { myState.hotThresholdCpu = value }

    var hotThresholdMemory: Double
        get() = myState.hotThresholdMemory
        set(value) { myState.hotThresholdMemory = value }

    var isVisible: Boolean
        get() = myState.isVisible
        set(value) { myState.isVisible = value }

    var dataSource: String
        get() = myState.dataSource
        set(value) { myState.dataSource = value }

    /** Check if basic authentication is configured */
    val hasBasicAuth: Boolean
        get() = basicAuthUsername.isNotBlank() && basicAuthPassword.isNotBlank()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /**
     * Builds Pyroscope query string based on current profile type
     */
    fun buildPyroscopeQuery(): String {
        return when (profileType) {
            "CPU" -> "process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"$serviceName\"}"
            "Memory" -> "memory:alloc_in_new_tlab_bytes:bytes:space:bytes{service_name=\"$serviceName\"}"
            else -> "process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"$serviceName\"}"
        }
    }

    /**
     * Validates and updates settings atomically
     * @return Error message if validation fails, null if successful
     */
    fun validateAndSet(
        baseUrl: String = this.baseUrl,
        basicAuthUsername: String = this.basicAuthUsername,
        basicAuthPassword: String = this.basicAuthPassword,
        profileType: String = this.profileType,
        serviceName: String = this.serviceName,
        timeWindow: String = this.timeWindow,
        hotThresholdCpu: Double = this.hotThresholdCpu,
        hotThresholdMemory: Double = this.hotThresholdMemory
    ): String? {
        // Validate URL format
        val url = baseUrl.trim().removeSuffix("/")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Base URL must start with http:// or https://"
        }

        // Validate service name
        if (serviceName.trim().isEmpty()) {
            return "Service name cannot be empty"
        }

        // Validate authentication consistency
        val username = basicAuthUsername.trim()
        val password = basicAuthPassword.trim()
        if (username.isNotEmpty() && password.isEmpty()) {
            return "Basic auth password is required when username is provided"
        }

        // Validate profile type
        if (profileType !in setOf("CPU", "Memory", "Wall")) {
            return "Profile type must be CPU, Memory, or Wall"
        }

        // Validate time window
        val allowedTimeWindows = setOf("now-15m", "now-30m", "now-1h", "now-1d", "now-3d", "now-1w")
        if (timeWindow !in allowedTimeWindows) {
            return "Time must be one of: ${allowedTimeWindows.joinToString(", ")}"
        }

        // Validate thresholds
        if (hotThresholdCpu < 0.001 || hotThresholdCpu > 10000.0) {
            return "CPU hot threshold must be between 0.001 and 10000 ms"
        }
        if (hotThresholdMemory < 0.01 || hotThresholdMemory > 10000.0) {
            return "Memory hot threshold must be between 0.01 and 10000 MB"
        }

        // All validations passed - update settings
        this.baseUrl = url
        this.basicAuthUsername = username
        this.basicAuthPassword = password
        this.profileType = profileType
        this.serviceName = serviceName.trim()
        this.timeWindow = timeWindow
        this.hotThresholdCpu = hotThresholdCpu
        this.hotThresholdMemory = hotThresholdMemory

        return null
    }

    /** Mark data source as file-based */
    fun setDataSourceFile() {
        dataSource = "FILE"
    }

    /** Mark data source as Pyroscope */
    fun setDataSourcePyroscope() {
        dataSource = "PYROSCOPE"
    }
}