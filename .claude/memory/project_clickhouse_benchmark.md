---
name: ClickHouse Benchmark Plugin — project structure
description: Architecture and file layout of the ClickHouse Benchmark IntelliJ IDEA plugin
type: project
---

Plugin scaffolded with IntelliJ Platform Gradle Plugin v2 (version 2.13.1), targeting IDEA 2025.2 Community Edition.

**Why:** User wants a plugin to run selected SQL queries as benchmarks against ClickHouse and display results.

**How to apply:** Use this as reference when the user asks to extend or debug any component.

## Key files

- `build.gradle.kts` — adds `com.clickhouse:clickhouse-jdbc:0.6.5` with `http` classifier (fat jar,
  `isTransitive = false`)
- `gradle/libs.versions.toml` — version catalog including `clickhouseJdbc = "0.6.5"`
- `gradle.properties` — plugin group `com.github.andrey5608.clickhousebenchmarkideaplugin`,
  `platformVersion = 2025.2.6.1`

## Source packages

```
src/main/kotlin/.../
  model/BenchmarkResult.kt          — data class (elapsedMs, rowsRead, bytesRead, memoryUsageBytes)
  settings/ClickHouseConnectionSettings.kt  — PersistentStateComponent (host, port, db, user, pass)
  settings/ClickHouseSettingsConfigurable.kt — Settings > Tools > ClickHouse Benchmark panel
  services/BenchmarkResultsService.kt — app service holding List<BenchmarkResult>, notifies listeners
  services/ClickHouseService.kt      — project service; runs JDBC query, times it, fetches system.query_log stats
  actions/RunBenchmarkAction.kt      — AnAction on editor selection; spawns Task.Backgroundable
  toolWindow/BenchmarkToolWindowFactory.kt — bottom tool window with JBTable of results + Clear toolbar
  MyBundle.kt                        — DynamicBundle helper (kept from template)
```

## plugin.xml registrations

- applicationService: ClickHouseConnectionSettings, BenchmarkResultsService
- projectService: ClickHouseService
- applicationConfigurable (parentId=tools): ClickHouseSettingsConfigurable
- toolWindow id="ClickHouse Benchmark", anchor=bottom: BenchmarkToolWindowFactory
- action id="ClickHouseBenchmark.RunBenchmark": RunBenchmarkAction (ToolsMenu + EditorPopupMenu + Ctrl+Shift+F10)

## JDBC approach

- URL: `jdbc:ch://host:port/database`
- Driver class: `com.clickhouse.jdbc.ClickHouseDriver`
- TODO: set `query_id` via `SET query_id='...'`, then after query run queries `system.query_log` for `read_bytes` /
  `memory_usage`
- Row count from iterating ResultSet (client-side)
