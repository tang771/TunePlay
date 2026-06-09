# TunePlay

一个基于 Android 开发的音乐播放器项目。

## 功能

- 用户登录
- 音乐播放
- 每日推荐
- 音乐库管理
- 个人中心

## 技术栈

- Android
- Kotlin
- Gradle

## 项目运行

1. 使用 Android Studio 打开项目
2. 等待 Gradle 同步完成
3. 连接模拟器或真机
4. 点击 Run 运行项目

## 项目结构

```text
TunePlay/
├── app/                  # Android 应用主模块（页面、逻辑、数据）
├── docs/                 # 项目设计文档与产品规格说明
│   └── superpowers/      # 核心设计（如 player-redesign-design.md）
├── gradle/               # Gradle 编译包与依赖管理
├── stream-relay.js       # [流媒体中转/转发服务的 Node.js 脚本]
├── build.gradle.kts      # 项目全局构建配置
└── settings.gradle.kts   # 模块与依赖源声明

## 作者

sunaet
