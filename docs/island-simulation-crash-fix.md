# 超级岛按钮空标题引发 SystemUI 崩溃

## 设备证据（2026-10-19）

设备：25098PN5AC / pandora，HyperOS `OS4.0.0.37.XBLCNXM`，SystemUI `17.03.260226.r`，系统界面插件 `18.3.2.22.0`。

只读抓取 `logcat`、DropBox、进程退出信息、通知状态和 LSPosed 日志。普通 crash 缓冲区未保留本次 Java 栈，DropBox 的 `system_app_crash` 记录包含 22:30:58 至 22:31:33 的五次相同异常：

```text
java.lang.NullPointerException: Attempt to invoke virtual method
'int java.lang.String.length()' on a null object reference
    at java.io.StringReader.<init>(StringReader.java:50)
    at android.text.HtmlToSpannedConverter.convert(Html.java:761)
    at android.text.Html.fromHtml(Html.java:247)
    at android.text.Html.fromHtml(Html.java:190)
    at miui.systemui.notification.focus.moduleV3.ModuleTextButton5ViewHolder.bind(SourceFile:354)
```

原始抓取位于 `.reverse-tmp/systemui-crash-20261019/`，关键文件为 `system-app-crashes.txt`。同版本插件反编译文件 `MiuiSystemUIPlugin-18.3.2.22.0/sources/miui/systemui/notification/focus/moduleV3/ModuleTextButton5ViewHolder.java` 的 `bind` 直接执行 `Html.fromHtml(actionInfo.getActionTitle())`，该字段为空时没有保护。竖屏、横屏装饰布局的按钮 5 实现同样直接读取 JSON。

## 原因与修复边界

预约和订单待支付均使用 `highlightInfoV3.actionInfo`。旧模拟数据只写 `action` 和 `type`，按钮标题仅存在于 `miui.focus.actions` 的 `Notification.Action.title`，并不会补入插件解析的 `ActionInfo.actionTitle`，因此会触发上述空指针。该堆栈能确定按钮组件，不能单凭堆栈区分两类模拟。

- `IslandSimulationPayload.kt` 的共同动作构造器补充 JSON `actionTitle`，涵盖按钮 1、提示按钮、文件分享按钮 4、预约/待支付按钮 5 和 Call Kit 识别图动作。
- 文件分享的拒绝按钮使用 `secondaryAction`，接收按钮使用 `action`，避免显示同一标题。
- 动作键、PendingIntent、接收器、系统插件及全局 hook 均保持原调用关系；修复由模块应用的通知生产端完成。
- `IslandSimulator.kt` 的运动功能图标和通话关闭图标回退到模块已有资源，落实图片未导入时的资源回退要求。
- 没有修改 `HookSettings.kt`、其序列化、无关通知材质或 hook 生命周期。

## 验证

- `tests/island-simulation/run.ps1` 通过。回归检查遍历十一类型的所有 JSON 动作，验证标题存在且等于对应设置，包含文件分享两个不同标题。原先只有动作键的输出无法通过该检查。
- 原有模板、图片、深浅色、背屏资格、3072 字节 UTF-8 上限、计时和全部倒计时检查通过。
- `.\gradlew.bat :app:assembleDebug` 通过（BUILD SUCCESSFUL），APK：`D:\ReverseProject\HyperChanger\app\build\outputs\apk\debug\app-debug.apk`。
- `git diff --check` 通过；三份语言 JSON 解析及超级岛翻译键一致性检查通过。
- 未安装修复 APK，未重启或结束 SystemUI。以上为设备日志定位、源码修复、静态回归与构建验证，修复后的真机渲染尚未验证。
