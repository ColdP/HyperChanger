# 音量面板布局

音量布局由 `VolumePanelLayoutSettings.kt` 独立保存、加载及同步远程偏好；UI `VolumePanelLayoutControls` 在“系统界面与材质调整”的音量面板卡片中显示，独立于材质开关。布局设置在应用连接 Xposed 服务时同步，运行时由偏好监听缓存，触摸和动画回调不重复读取远程偏好。

- 自定义大小：宽度偏移 -30…60 dp、高度偏移 -64…96 dp，默认 0，启用后通过已有显隐动画显示滑块。
- 静音/勿扰、上部展开按钮分别隐藏；仅在隐藏展开按钮时显示“长按展开音量面板”。长按展开还会在运行时检查两个开关均启用。
- 自定义圆角：独立开关及 -40…80 dp 滑块，默认 0，原生半径加偏移后最小为 0；旧绝对圆角键不再参与 Hook。

## 仅调整收起音量条大小

SystemUI 两份反编译中的实体按键音量面板由插件实现。插件 18.2.2.2.0 / 18.3.2.22.0 的 `VolumeColumnRes.getWidth/getHeight` 为收起、展开、控制中心分别选择资源。参考 HyperLight `ic2` / `wg2` 的尺寸与触摸目标，但不使用其绝对高度替换和缩放动画。

尺寸偏移只匹配：

| 目标 | 资源 |
| --- | --- |
| 收起宽度 | `miui_volume_column_width`、`flip_tiny_miui_volume_column_width` |
| 收起高度 | `miui_volume_column_height`、`flip_tiny_miui_volume_column_height` |
| 收起静音/勿扰宽度 | `o3_miui_ringer_btn_width` |

`VolumeColumn.setSliderSize`、`setProgressViewSize`、`setProgressViewBgSize` 同时读取这些原生输入，避免玻璃、进度、背景错位；尺寸以资源原始 dp 加偏移，最小 1 dp，不对已经修改的 View 再做加法。

展开资源 `o3_miui_volume_expend_width/height`、单通知展开宽度 `o3_miui_volume_notify_width`、展开静音/勿扰宽度 `o3_miui_ringer_btn_width_expended` 均不替换；控制中心专用宽高及按钮资源也不替换。系统展开/收起流程通过对应资源重新设置尺寸，因此展开终态保留系统默认大小，收起时重新应用偏移。圆角不受材质开关限制，并排除控制中心专用圆角资源。

## 按钮与长按

`MiuiVolumeDialogView.onFinishInflate/updateFooterVisibility` 执行后按偏好隐藏 `mRingerModeLayout` / `mExpandButton`。`MiuiVolumeSeekBar.onTouchEvent` 接入 `VolumePanelLongPress`，仅在收起的实体按键面板识别单指长按，用系统时限与触摸容差；拖动、多指、抬手、取消会撤销任务。触发前结束原生 SeekBar 跟踪，然后调用面板原有 `expandListener`，沿用系统展开动画。弱引用及触发前状态复查避免对已经分离/隐藏的视图展开。

## 验证

`tests/soft-glass/run.ps1` 验证布局偏好持久化及远程恢复、旧绝对圆角隔离、开关依赖、宽高正负边界、圆角归零、连续收起/展开资源查询、展开/控制中心全部透传。三份语言 JSON 与测试 APK 内打包语言均校验。实际动画、长按和观感仍需设备验证；不安装 APK 或重启 SystemUI。
