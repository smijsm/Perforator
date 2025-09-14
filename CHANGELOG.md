# Changelog

All notable changes to the Perforator plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2025-09-14

### 🎉 Initial Release — Continuous Profiling in Your IDE

#### ✨ Core Functionality
- **Inline Performance Badges**
    - Gutter badges showing per-line CPU time (ms) or Memory allocation (MB)
    - Visual “hot” highlighting based on configurable thresholds
- **Toolbar Controls**
    - Switch between CPU and Memory profiles
    - Time window selection: `now-15m`, `now-30m`, `now-1h`, `now-1d`, `now-3d`, `now-1w`
    - Hot threshold fields with validation and unit hints
- **Fetch From Grafana Pyroscope**
    - One-click fetch via Tools → Fetch Profiling Data
    - Automatic refresh and re-render of badges on successful fetch
    - Proper cleanup and revalidation of UI after updates

#### 🔒 Connectivity & Auth
- **Optional Basic Authentication**
    - Username/password stored in plugin settings
    - Sent via `Authorization: Basic` header when configured
    - Works with secured Grafana Pyroscope instances

#### 🧠 MCP-Oriented AI Prompts
- **Right-Click to Copy Prompt**
    - No dialog window; copies directly to clipboard with notification (on right-click)
    - Prompts are tailored for Grafana MCP server (https://github.com/grafana/mcp-grafana) which can be used with JetBrains Junie, Claude Code, or any other AI agent which supports MCP servers.
    - Includes `fetch_pyroscope_profile` with `start_rfc_3339` and `end_rfc_3339` computed from the selected time window
    - Method reference resolution attempts fully qualified form (e.g., `org/example/MyService.doWork`)

#### 🧩 Language & IDE Integration
- **Languages**
    - Java and Kotlin support for method and class resolution
- **IDE Integration**
    - Annotator-based badge rendering
    - Project settings page (Settings → Tools → Perforator)
    - Tools menu action for data fetching
    - Automatic toolbar injection in open editors

#### 🧱 Robustness & UX
- **Safe Rendering**
    - Clears old badges when profile type changes to avoid mixed units
    - Deduplication of badges per line within a single pass
- **Resilient Networking**
    - URL validation and graceful error handling
    - Logs key steps for troubleshooting fetch and parse flow

#### 📋 Supported Configurations
- **IDE Compatibility**: IntelliJ IDEA 2025.1.3+
- **Java Compatibility**: Java 17+
- **Backend**: Grafana Pyroscope
- **Profiles**: CPU (nanoseconds → ms), Memory (bytes → MB)

---
**Note**: This changelog will be updated with each release. For the latest changes, please check our GitHub repository.
