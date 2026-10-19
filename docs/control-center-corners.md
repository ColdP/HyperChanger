# 控制中心圆角

顶部独立卡片仅包含“自定义控制中心圆角”开关，不显示 SmallTitle。开启后使用项目现有淡入/缩放显隐动画显示“圆角参数调整”卡片，包含顶部控制按钮、媒体卡片、音量/亮度条、圆形按钮、融合台设备中心卡片、展开面板六个滑块，均为 -40…80 dp 系统原值偏移，默认 0。

独立 `ControlCenterRadiusSettings.kt` 管理所有新键、加载、保存、远端同步与运行时缓存；不修改 `HookSettings`，也不读取旧的逐项开关和绝对圆角值，避免旧 24 dp 被当成偏移。

分析依据是 HyperLight `g3` 的圆角目标，以及插件 18.3.2.22.0 源码：

- 顶部按钮：`QSCardItemView.setCornerRadius`，同步原生背景与玻璃轮廓。
- 媒体：`MediaPlayerController.MediaPlayerViewHolder.setCornerRadius` 与 `MediaPlayerPanel.setCornerRadius`，分别覆盖纯色和材质分支；嵌套调用按分类保护，防止重复加偏移。
- 音量/亮度：`ToggleSliderViewHolder.setOutlineRadius`、`BrightnessPanelSliderDelegate.setOutlineRadius`。只调整外轮廓和对应背景，不改变前景进度条端部圆角。二级音量 `VolumePanelController` 的 setter 为空，通过原生 getter 更新过渡几何。
- 圆形按钮：`QSTileItemIconView.setCornerRadius`，以系统输入的圆形半径为基准。
- 融合台卡片：`DeviceCenterEntryFrameLayout` 的 `external_entry_background` 是原生圆角矩形，读取当前 GradientDrawable 的系统半径，调整后同步玻璃轮廓；不使用未布局的 View 高度猜测基准。覆盖 inflate、背景替换和材质刷新。
- 展开面板：`SecondaryPanelControllerBase.setContentBgRadius`，以系统 `detail_panel_background_corner_radius` 为基准；材质背景替换后同步当前原生圆角。

偏移按屏幕 density 转换为像素，半径下限为 0。弱引用保存各对象系统输入与最近输出，循环 getter/setter 与重复背景刷新不累加偏移；系统输入变化时替换基准。0 或关闭时透传原值，已记录对象在下次原生更新时恢复基准及原始轮廓提供者。未主动重启 SystemUI。

测试覆盖旧绝对设置隔离、六项偏好的本地/远程往返与新存储恢复、数值裁剪、dp/px 换算、负值夹零、默认/关闭透传、重复回调不累加、系统基准变化及从直角恢复默认。设备上的材质切换、展开过渡和最终观感仍需实机验证。
