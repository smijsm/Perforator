# Perf🕳️rat🕳️r – Continuous Profiling Integration for JetBrains IDE

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![IntelliJ Plugin](https://img.shields.io/badge/IntelliJ-Plugin-blue.svg)](https://plugins.jetbrains.com/)
[![Backend: Grafana Pyroscope](https://img.shields.io/badge/Backend-Grafana%20Pyroscope-orange.svg)](#)

Bring continuous profiling data from Grafana Pyroscope into the editor, highlight hot spots inline, and generate MCP-ready AI optimization prompts for Junie, Claude Code or other AI agents.

---

## 🎯 Why Perforator

Performance work is most effective when grounded in production behavior. Perforator fetches real continuous profiling data and overlays it next to the code being profiled, so optimization decisions are made with context.

---

## ✨ Features

- 🔥 Inline gutter badges with CPU time or memory usage per method
- 🧭 Editor toolbar to switch CPU/Memory, select time windows, and set hot thresholds
- 🔒 Optional Basic Auth for secured Grafana Pyroscope instances
- ⚡ One-click fetch via Tools menu
- 🧠 Right-click a badge to copy an AI optimization prompt
    - Prompts are tailored for Grafana MCP server (https://github.com/grafana/mcp-grafana) which can be used with JetBrains Junie, Claude Code, or any other AI agent which supports MCP servers.
- 🧩 Language support: Java and Kotlin

---

## 🚀 Quick Start

### Prerequisites
- IntelliJ IDEA 2025.1.3 or later
- Access to a Grafana Pyroscope instance (URL + optional Basic Auth)

### Installation
1. Download the plugin from the JetBrains Plugin Repository
2. Install via: File → Settings → Plugins → Install Plugin from Disk
3. Restart IntelliJ IDEA

### Configuration
Open Settings → Tools → Perforator and configure:
- Base URL: e.g. `http://localhost:4040`
- Optional Basic Auth: username/password
- Service Name: matches your Pyroscope label (e.g. `service_name`)
- Profile Type: `CPU` or `Memory`
- Time Window: `now-15m`, `now-30m`, `now-1h`, `now-1d`, `now-3d`, `now-1w`

---

## 🎯 How to Use

1. Open a source file that belongs to the profiled service.
2. Use Tools → Fetch Profiling Data.
3. The Perforator toolbar appears at the top of the editor:
    - Switch between CPU/Memory profiles and pick a time window
    - Adjust hot thresholds (ms for CPU, MB for Memory)
4. Gutter badges show inline metrics next to relevant lines.
5. Right-click a badge to copy an AI prompt.
6. Paste this into an agent connected to Grafana MCP server (usable with Junie, Claude Code, or any MCP-capable agent).

---

## 🏗️ Supported Inputs

- Profile Types
    - CPU: displays execution time in milliseconds
    - Memory: displays allocation in megabytes
- Time Windows
    - `now-15m`, `now-30m`, `now-1h`, `now-1d`, `now-3d`, `now-1w`
- Languages
    - Java, Kotlin

---

## 🗺️ Roadmap

- Additional continuous profiling backends beyond Grafana Pyroscope
- More languages (e.g., Scala)
- Integrated flamegraph navigation

---

## 🧩 Development

- Java 17+
- Gradle IntelliJ plugin
- Run locally: `./gradlew runIde`

### IDE Integration Points
- Tools → Fetch Profiling Data
- Settings → Tools → Perforator

---

## 📄 License

MIT License — see `LICENSE`.

---

## 👨‍💻 Author

**Michael Solovev** — [smijsm@gmail.com](mailto:smijsm@gmail.com)

---

Optimize where it matters — with real, continuous profiling data in your editor.
