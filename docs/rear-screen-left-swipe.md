# 背屏左滑手势

## 实现边界与反编译依据

- HyperLight 1.2.2 的 `f3` case 14、`r71.p`、`l32` case 12/13 和 `k62`：默认关闭；在 `SubScreenLauncher.dispatchTouchEvent(MotionEvent)` 注入右侧起手的左滑，打开用户选择的应用；以 `a(String, float)` 驱动系统背景模糊，返回桌面时复位。
- SubScreen RELEASE-1.0.2609181659 的 `com.xiaomi.subscreencenter.SubScreenLauncher` 确认上述触摸入口、生命周期方法以及 `a(String, float)` / `B(String, float)` 的实际实现。原触摸分发会取消子 View 的事件；注入手势也必须只取消一次，避免误触。
- 新功能由 `OthersHooks` 独立安装入口持有，仅作用于 `com.xiaomi.subscreencenter` 中实际加载该 Activity 的进程，使用现有包回调的 `param.defaultClassLoader`。`HyperSystemUiModule` 仅增加该包分支的一处委托，不迁移现有代码，不修改 framework 分发或其他 Hook。
- 设置存储、读写和远程同步置于新的 `RearScreenGestureSettings.kt`，不扩展 `HookSettings`。开关下提供应用选择；未选择应用时不接管触摸。
- 手势只从 Activity 右侧 40% 区域起手，方向判断沿用 HyperLight 的 10dp / 水平垂直比 1.5 / 150dp 行程 / 75% 完成阈值。取消和多指触摸不得启动应用。状态按 Activity 隔离，暂停/恢复/销毁时清理自身动画与模糊，不影响非注入手势的背景。
- 风险：混淆方法在其他系统版本中可能变化；目标应用能否在背屏运行仍取决于系统自身能力。构建和静态检查无法替代实机手势、系统手势冲突与背屏启动验证。

## 验证

- 实际新增 `RearScreenGestureSettings.kt`、`RearScreenLeftSwipeGesture.kt`；`OthersHooks.installRearScreenLeftSwipeHook` 安装触摸入口及 `onPause/onResume/onDestroy` 清理回调，入口只在 `HyperSystemUiModule` 的 `SUBSCREEN_CENTER` 分支委托一次。`Z` 是原始 DEX 字段名（JADX 展示为 `f5788Z`），用于保留原版编辑/确认弹窗期间禁止手势的条件；`c()` 用于避免覆盖其他功能正在使用的模糊层。
- 应用内增加默认关闭的开关，复用已有显隐动画；新增 `REAR_SWIPE_APP` 二级页面，支持搜索、单选、默认隐藏系统应用、工具栏三点菜单切换；已有选择时展示“编辑 / 清除 / 取消”，清除后点击直接选应用。设置页面监听独立偏好，离线编辑和明确清除不会被旧远程值覆盖。
- `RestartRecommendationSnapshot` 纳入两个默认值，`RestartRecommendationTracker` 仅建议重启背屏作用域。没有移动其他 Hook、复制共享实现、修改 `HookSettings` 或 framework 分发。
- `.\tests\rear-screen-gesture\run.ps1` 通过：密度换算、完成阈值、短滑、错误起点、竖滑/右滑透传、回撤、取消复位、重复完成、父页面旧快照、离线保存、远程恢复、明确清除持久化。
- `.\tests\restart-recommendations\run.ps1` 通过：新开关和目标应用的修改仅映射背屏作用域，写入默认值不产生重启提示。
- 英文、日文和语言示例三份 JSON 均可解析且包含新增键；`git diff --check` 通过。
- 按要求执行 `.\gradlew.bat :app:assembleDebug`，最终 `BUILD SUCCESSFUL in 30s`。APK：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`。
- 未执行 ADB、安装或进程重启。实际设备上的手势与现有系统交互、动画效果、目标应用背屏显示仍未验证。

## 后续修复：对话框与应用名称

- “编辑 / 清除 / 取消”改为纵向三个全宽按钮，保留原有对话框组件、按钮行为和翻译。
- 应用列表及已选应用摘要共用 `ApplicationLabels.kt`。通过 `LocaleManager.systemLocales` 获取设备语言，并在独立的目标应用配置 Context 中解析 `labelRes`，避免 `ApplicationInfo.loadLabel()` 使用模块资源语言配置或旧标签缓存时回退英文。保留固定名称和资源读取失败时的回退，不修改共享 Resources。
- Compose 按配置与系统语言刷新列表及摘要；应用名属于其他应用资源，不通过模块的翻译表覆盖。
- `git diff --check` 和三份语言 JSON 解析通过；无新增或修改模块文案。执行 `.\gradlew.bat :app:assembleDebug`，`BUILD SUCCESSFUL in 35s`，生成的 Debug APK 路径同上。名称语言和对话框布局尚未实机验证。
