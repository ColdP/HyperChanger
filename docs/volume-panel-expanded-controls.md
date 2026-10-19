# 音量展开面板按钮与圆角修复

后续按用户要求恢复音量条与展开面板共用一个“圆角”滑块，删除独立展开圆角设置；此前按钮显示修复保留。当前实现与验证见 [notification-text-and-flashlight.md](notification-text-and-flashlight.md)，下文保留原轮次记录。

## 修改前边界

- 保留已有未提交修改。此次为行为修复与设置新增，不进行分类迁移。
- `HyperSystemUiModule.installLoadedVolumePanelHook` 现有 `MiuiVolumeDialogView` 分支负责按钮，保留原共享安装器、ClassLoader、安装 guard、材质与长按逻辑，其他 Hook 类不改动。目标为 SystemUI 中插件加载器加载的 `com.android.systemui.miui.volume.MiuiVolumeDialogView`。
- HyperLight `wg2` case 21 先调用 `isExpanded()`，仅在收起时隐藏 `mRingerModeLayout`；展开时显示。现实现无条件隐藏，导致展开设置一并消失。
- 插件 18.3.2.22.0 反编译确认 `updateFooterVisibility(boolean)` 原方法决定静音/勿扰容器是否显示，展开仅隐藏展开按钮；`onExpandStateUpdated(boolean)` 负责切换动画和子布局。修改需保存原系统可见状态，在展开/收起切换后恢复/隐藏，不能把系统因锁屏或其他条件隐藏的容器强制显示。
- `VolumeColumnRes.getRadius` 的 `o3_miui_volume_radius` / `o3_miui_tiny_volume_radius` 是音量条圆角；`MiuiVolumeDialogRes.getBgRadius` 的 `o3_miui_volume_bg_radius` 是展开背景圆角，传统 `Util.getBgRadius` 的 `miui_volume_bg_radius_expanded` / `miui_volume_blur_bg_radius` 也属于展开背景。两类偏移分开，0 均透传系统值。
- 新展开圆角偏好存放于新文件 `VolumePanelExpandedCornerSettings.kt`（范围 -20..30），沿用独立于材质开关的现有圆角开关；不扩展 `HookSettings`。原有滑块重命名“音量条圆角”，保留原范围。
- 在现有 `VolumePanelGlassRuntimeSettings` 偏好监听中增加新设置快照，沿用已有资源 Hook，不新增重复资源 Hook 或动画期间远程读取。
- 风险：不同插件版本的资源/方法名可能变化；展开动画及锁屏条件下的显示需要实机验证。构建验证不能证明运行时效果。

## 验证结果

- 实际修改：`HyperSystemUiModule` 原按钮 Hook 增加 `onExpandStateUpdated` 回调，所有回调先执行一次原方法，再以弱引用 View 状态恢复/隐藏按钮。新 `VolumePanelRingerVisibility.kt` 保存系统给出的 VISIBLE/INVISIBLE/GONE，不强制显示系统隐藏的容器；控制中心不应用收起隐藏。原展开按钮隐藏和长按安装路径保持原有实现。
- `VolumePanelLayoutSettings` 只处理音量条圆角资源；新 `VolumePanelExpandedCornerSettings.kt` 单独处理展开背景圆角资源，存储 `volume_panel_expanded_corner_offset`、默认 0、范围 -20..30；原音量条偏好及 -40..80 范围保留。均复用原尺寸 Hook，圆角调整独立于材质开关，偏移从系统值计算。
- UI 将开关命名为“自定义音量圆角”，以原显隐动画展示两个滑块。英文、日文、语言示例同步新增文案，三份 JSON 均通过解析。
- `.\tests\volume-panel-controls\run.ps1` 通过：50 次展开/收起恢复、停用开关恢复、系统隐藏条件、控制中心透传、圆角资源隔离、0 透传、边界及偏好持久化/远程同步。
- `.\tests\soft-glass\run.ps1` 与 `.\tests\restart-recommendations\run.ps1` 均通过；软玻璃测试编译清单同步加入新设置文件依赖。
- `.\gradlew.bat :app:assembleDebug`：`BUILD SUCCESSFUL in 2m 2s`，APK 为 `D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`。`git diff --check` 通过。
- 对照插件 18.2.2.2.0 与 18.3.2.22.0 确认相关方法、字段及圆角资源一致。没有安装 APK 或执行 ADB；实机展开动画、系统隐藏条件和不同材质模式的效果尚未验证。
