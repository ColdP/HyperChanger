# Agent Instructions

- Do not use, create, copy, or remove files in `apk-output-final`.
- Debug APKs must remain at Gradle's default path: `app/build/outputs/apk/debug/app-debug.apk`.
- Keep reverse-engineering and build temporary files on the D: workspace (for example `.reverse-tmp`, `.build-tmp`, and `.gradle-home`), never on C:.
- Do not run any device, SystemUI, or system-service restart command unless the user has explicitly authorized that restart in the current request. Do not restart merely for testing.
- Do not use the JADX GUI; use JADX only through its command-line interface.
- 构建测试必须输出 APK。
- 发布前须将待上传的 APK 重命名为 `HyperChanger-v<versionName>.apk`，文件名模板为 `HyperChanger-v{versionName}.apk`（例如版本 `1.1.1` 对应 `HyperChanger-v1.1.1.apk`）；Gradle 默认输出文件可保留不变。
- 新增界面及其入口必须按照项目现有语言机制适配多语言。
- 所有新增可见文案必须使用 `tr(...)`，并同步维护内置语言资源与 `languages/example.json`。
