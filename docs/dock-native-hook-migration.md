# Dock 原生 Hook 迁移审计

## 范围

本次适配针对 `com.miui.home` Launcher 进程内的 Flutter/AOT `libapp.so`，只处理 Dock 的能力、背景和布局分支。Hook 入口仍由 `folder_entry.cpp` 负责，在 Launcher 子进程及 `libapp.so` 加载回调后启动 `folder_spacing.cpp` 的配置观察线程；没有改变 SystemUI、框架包或 Dock 动画入口。

配置继续由现有 `MaterialSettings` 扩展读取，未增加 `HookSettings.kt` 字段，也未改变 HomeTweaks 的配置文件格式。Dock 的伪装只作用于 Dock 目标：背景分支伪装为支持 Dock 的设备，三个布局分支局部选择无字模式路径；没有修改全局 `Utilities.isNoWordModel`，因此不会隐藏桌面其他图标文字。现有非无字模式错位修复仍由本模块已有的局部 Dock 分支负责，未照搬 HomeTweaks 的实现。

## 反编译证据

反编译结果按仓库模式保存在：

- `.reverse-tmp/HomeTweaks-3.4.261021-2050`
- `.reverse-tmp/launcher-8.01.02.8583`

8583 输入 APK 的 `libapp.so` SHA-256 为 `6f5274f435cc07aab165ce5315d1d35afab559a659afd6200379d309c9d08b91`。APK 的 `versionCode` 为 `801028583`，`versionName` 为 `RELEASE-8.01.02.8583-260918-10161026-R`。以下地址均是该 `libapp.so` 映像内的 RVA，不是进程绝对地址：

| 目标 | RVA | 反编译确认 |
| --- | ---: | --- |
| `DeviceConfig.supportShowDockBackground` | `0x101395c` | support 谓词；首词为 `0xb84d7022` |
| `_DockBackgroundViewState.build` | `0x11786a4` | Dock 背景能力分支 `0x37200101` |
| `HotseatLayerGetxController.backgroundLayout` | `0xee21d0` | 局部无字分支 `0x37200220` |
| `HotseatLayerGetxController.calculateDockOffsetY` | `0x8f60f0` | 局部无字分支 `0x37200160` |
| `HotseatLayerGetxController.calculatePositionY` | `0xbe7eac` | 局部无字分支 `0x372000a0` |

support 唯一签名为：

```text
0xb84d7022 0x8b1c8042 0x36200062 0x910082c0 0x14000007
```

四个目标词和 support 签名已用脚本从 supplied ELF 重读验证，静态结果为 `PASS`。HomeTweaks 3.4 的 `native_init` 与 `/data/user_de/0/com.miui.home/files/hometweaks.bin` 仅作为行为和版本结构参考；HyperChanger 继续使用自己的 `hyperpatch.bin` 观察路径和独立的 native library。

## 实现与边界

- `home_native_dock.h` 新增 8583 独立 support signature 和五个 RVA 常量。
- `folder_spacing.cpp` 新增独立扫描向量 `release8583_supports`，要求签名唯一。
- 只有四个 RVA 的 live instruction 全部匹配时才安装分支；映像来源、映射归属和写入前 live code 仍经过现有校验。
- support 分支写入 `B` 到支持对象路径，背景分支跳过能力回退，三个布局分支写入局部 NOP，保留原始词用于关闭时恢复。
- 8583 分支与 6245、7722、7748 分支分别门控；旧版地址不会因 load bias 或版本号猜测应用到 8583。

## 风险与验证状态

- `STATICALLY_VERIFIED`：8583 ELF 哈希、support 签名唯一性、四个目标词、源码常量和扫描路径。
- `STATICALLY_VERIFIED`：`git diff --check`。
- `UNKNOWN`：尚未在真实 HyperOS 设备加载该版本 Launcher，未验证 Dock 背景观感、无字/有字切换后的错位结果、关闭恢复和设备稳定性。
- `UNKNOWN`：当前环境未提供目标设备的 build fingerprint、ABI、注入框架版本、Rust/NDK 版本和运行日志，因此不能把静态地址当作跨版本通用地址。

设备验证应单独记录加载日志、实际 Launcher 版本和复现步骤；本次没有执行安装、重启或 SystemUI 操作。
