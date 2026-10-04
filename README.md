# 酒馆 · 原生版（Kotlin + Jetpack Compose）

Flet 版的重写。计划见 `../fletapp/NATIVE_PLAN.md`。

- 纯逻辑核心在 `:domain`（纯 Kotlin/JVM，不依赖 Android，可直接跑单测）
- 界面在 `:app`（Compose）
- 行为以 Flet 版的 Python 实现与 204 项核心测试为准绳
