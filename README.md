# GC-Tune

[![GitHub Release](https://img.shields.io/github/v/release/Bebrazui/GC-Tune?style=flat-square&color=2ea44f)](https://github.com/Bebrazui/GC-Tune/releases/latest)
[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen.svg?style=flat-square)](https://android.com)
[![OriginOS](https://img.shields.io/badge/OriginOS-Supported-informational.svg?style=flat-square)](https://www.vivo.com/origin-os)

面向 OriginOS 与 vivo 设备的高级系统调优工具与提权通道实现。GC-Tune 借助 Game Cube（电竞模式）的执行环境，以系统级权限（`uid 1000` / `system`）执行命令、安装自定义 `.itz` 主题，并直接把自定义图标包的着色效果应用到 OriginOS 桌面，全程无需 root。

[下载最新版本](https://github.com/ShadowxInk/GCTUNE_CN/) &bull; [快速开始](#快速开始) &bull; [功能特性](#功能特性) &bull; [构建](#构建)

---

## 概述

GC-Tune 的核心是借助 Game Cube 特权子系统建立的一条 IPC 通道。通过这条通道可获得 `system`（uid 1000）执行能力，用来调整设备设置、替换系统主题，以及用 Material You 与自定义配色为图标着色。

### 核心能力

- **特权系统 Shell（`uid 1000`）**
  直接以 system 凭据运行管理级 shell 命令。唤醒机制由 `dumpsys SurfaceFlinger` 与 `logcat` 信号驱动，空闲时不做轮询，也不产生耗电。

- **OriginOS 主题修补（`.itz`）**
  导入自定义及第三方 `.itz` 主题包，清理商店专有元数据并归一化目录标识（`id`、`id3`、`uid`），使自定义主题可以在 iTheme 中预览并应用，不再受付费墙限制。

- **系统图标与自适应图标着色**
  将统一的图标着色与样式直接应用到 OriginOS 桌面：
  - Material 3 与自定义 HEX 颜色着色
  - 可调节的降低饱和度、提亮与着色强度
  - 自定义背景底板与圆角（squircle）半径调节
  - 兼容标准第三方图标包

- **无需 Root**
  不需要 root 权限，也无需解锁 bootloader。完全依托系统框架集成与常规标准权限（通过 ADB 授予 `WRITE_SECURE_SETTINGS`）运行。

---

## 快速开始

### 1. 安装

从 [Releases](https://github.com/Bebrazui/GC-Tune/releases/latest) 页面下载已签名的 APK，并安装到设备：

```bash
adb install -r gctune-signed.apk
```

### 2. 授予权限

安装完成后，通过 ADB 授予一次 `WRITE_SECURE_SETTINGS`：

```bash
adb shell pm grant com.poc.gctune android.permission.WRITE_SECURE_SETTINGS
```

### 3. 添加到 Game Cube

1. 打开 **设置** &rarr; **电竞模式**（Game Cube）。
2. 进入 **支持的游戏**（添加游戏）。
3. 在 **其他应用** 中选择 **GC Tune**，将其加入游戏列表。
4. 启动 GC Tune，引导页会确认通道连接已生效。

---

## 主题（.itz）安装流程

仓库原先只描述了安装与授权，没有写主题的具体操作，这里补充完整流程（实现见 `Main.java` 的 theme 段与 `ThemePatcher.java`）。

### 前置条件

- 已通过 ADB 授予 `WRITE_SECURE_SETTINGS`，并把 GC Tune 加入电竞模式游戏列表（否则 uid 1000 通道不可用）。
- iTheme 中已经存在**已下载的主题包**：安装命令会替换 `/data/bbkcore/theme/.dwd/.../T/*.itz` 这些本地主题文件；一个都没有时会提示「目录 .../T 中没有 .itz 文件」。自定义 `.itz` 必须是完整的主题包（内含 `description.xml`），结构与 iTheme 主题包一致。

### 操作步骤

1. 打开 GC Tune &rarr; **个性化** &rarr; **主题**（副标题「安装并应用 .itz」）。
2. 点 **选择 .itz**，用系统文件选择器挑一个 `.itz`。
   - App 优先直接使用文件的真实路径（仅限 `/storage` 下、不在 `Android/` 里的普通文件）；取不到时通过 MediaStore 复制到 `Download/gctune/theme.itz`，再不行复制到应用私有外部目录。
   - 若 `description.xml` 里带 `<id>` / `<id3>` / `<uid>`（制作自定义主题时常用的商店付费主题占位 id），App 会把它们替换为中性值 `1276336` / `100152289`，避免 iTheme 把自定义主题当成付费主题而显示价格；压缩包内其余文件逐字节原样保留。
   - 文件就绪后会显示大小与 MD5，状态卡提示「可以安装」。
3. 点 **安装并打开 iTheme**。
   - 首次执行需要按界面提示**打开一次电竞模式**（Game Cube 面板），命令才会以 uid 1000 执行；之后后台守护进程存活到重启，只需面板唤醒。
   - **安装前先备份**：先找出 `/data/bbkcore` 下所有 `*/T/*.itz`，把每个文件复制成同目录的 `<原文件名>.gctune-bak`；已有备份时不覆盖，所以这份备份永远是最原始的那一份。
   - 再用 MD5 校验来源文件（与选文件时算出的不一致会报 `SRC_STALE`），用所选文件逐个覆盖并回读校验 MD5。
   - **覆盖后把文件的修改时间改回原值**（`touch -r 备份 目标`）：iTheme 用「记录里的下载时间 == 文件的 lastModified」判断本地主题是否还有效，只要 mtime 变了，条目就会从本地主题列表里消失——这正是「装完主题，原来下载的主题不见了」的直接原因。
   - **强制停止 iTheme，并清除它的完整性指纹**（`shared_prefs/itz_integrity.xml`，键为 `resId_packageId_zip_md5` / `_xml_md5`）：iTheme 应用主题前会比对「zip 的 MD5 + description.xml 的 MD5」，不一致就返回 `Response(-1)` 直接拒绝应用。删除该记录后，iTheme 会为替换后的文件重新计算并保存。若 uid 1000 没有读取/写入该文件的权限，命令会输出 `INTEGRITY_NOREAD` / `INTEGRITY_NOPERM`，界面也会提示；这种情况不影响替换，只是应用主题时可能仍被拦截。
   - 随后重新打开 iTheme（包名由 `.dwd` 下的字母目录还原，例如 `c/o/m/b/b/k/t/h/e/m/e` &rarr; `com.bbk.theme`）。
   - 打开后等待约 6 秒复查：若 iTheme 把被替换的包判为无效并删除，命令会自动从 `.gctune-bak` 还原这个文件，并输出 `RESTORED:` 与 `LOST=` 计数。此时界面提示「iTheme 不认这个主题包：已还原原来的 N 个本地主题文件」——说明这个 `.itz` 与本机 iTheme/ROM 版本不兼容，需要换一个包，但**你原来的本地主题不会丢**。
4. 在 iTheme 中预览并应用主题——GC Tune 只负责替换主题包文件，「应用」这一步在 iTheme 里完成。

### 状态提示对照

| 界面提示 | 原因 |
| --- | --- |
| uid 1000 无响应——请打开游戏模式后重试 | 通道未激活或命令超时 |
| uid 1000 无法读取来源文件（SRC_UNREADABLE） | 暂存文件被移动/删除，或 uid 1000 无权读取 |
| 来源与所选文件不一致——请重新选择（SRC_STALE） | 暂存文件在选文件之后被覆盖过 |
| 目录 .../theme/.dwd/.../T 中没有 .itz 文件（NO_THEME_FOUND） | iTheme 里还没有可替换的本地主题包 |
| 无法确定 iTheme 包——详情见控制台（NO_PKG） | 主题包目录无法映射到已安装包名 |
| 写入失败——输出见控制台（Shell 标签页） | 覆盖 `.itz` 失败（权限/空间等） |
| GC Tune 不在游戏列表中——请添加应用后重试 | 未加入电竞模式游戏列表 |
| 已替换文件：N，已保留原修改时间（不会从本地主题列表消失），iTheme 已打开——请应用主题 | 成功，且 iTheme 没有丢弃替换后的包（末尾可能附带校验记录的清除结果） |
| iTheme 仍不认这个主题包：已还原原来的 N 个本地主题文件（下载的主题没有丢）——请换一个 .itz，或点「生成兼容包」用原主题包做模板 | 替换成功，但 iTheme 校验失败并删除了该包，已自动从备份还原 |
| 已替换文件：N，但 iTheme 移除了 M 个文件（备份仍在，可点「还原原主题」） | iTheme 删了文件且自动还原未覆盖到，需手动点「还原原主题」 |

### iTheme 的两道校验（为什么装完会「消失」或「无效」）

从 iTheme 23.8.5.1（`com.bbk.theme`）反汇编出来的两条硬性约束，正好对应两个症状：

| 校验 | 位置 | 触发症状 | 处理 |
| --- | --- | --- | --- |
| 本地主题记录里的 `downloadTime` 与文件 `lastModified` 是否一致 | `ResLocalScan.scanLocalResDir`：不一致时 `resItemMaps.remove(path)` 并打印 `scanLocalResDir remove file= ` | 文件还在、大小也没变，但**本地主题列表里没有这一项** | 覆盖后把 mtime 恢复成原值（1.2.5 起自动做） |
| `itz_integrity` 里保存的「zip MD5 + description.xml MD5」 | `ThemeIntegrityInterceptor.verifyIntegrity()` &rarr; `Companion.apply()` 不相等时返回 `Response(-1)` | 文件在、列表也在，但**应用时提示主题无效/无法应用** | 清除 `shared_prefs/itz_integrity.xml` 让 iTheme 重新计算（1.2.5 起自动尝试） |

补充两点：

- `scanLocalResDir` 只解析 `themes_*_temp.itz` 这类下载临时文件；普通 `.itz` 会被跳过，所以替换普通 `.itz` 不会在扫描阶段被删除——「消失」来自上面的 mtime 记录比对，而不是文件被删。
- 即使两道校验都过，iTheme 仍可能因为包结构判为非法。反汇编 `vf.a(path,resType,inner,check)` 可以看到它的准入条件：文件存在、能用自己的 zip 助手打开、**根目录有 `description.xml`**（由 `vf.isNewItzFile` 判断，失败时日志是 `canParseRes file is not new itz file, path=`），之后 `parseResItzFile` 才解析字段与摘要。任一步失败都会走到 `scanLocalResDir` 的 `File parse failed, delete.path=`，也就是**文件被 iTheme 删掉**。

### 生成兼容包（iTheme 不认你的 .itz 时）

iTheme 只认它自己生成的包：除了字段，它要求根目录的 `description.xml` 能被它解析（见上一节），外部拿来的 `.itz` 常常不满足。这时用「**生成兼容包**」（1.2.6 起）：

1. 先选好你的 `.itz`。
2. 点 **生成兼容包**：命令把 iTheme 已经管理的那份主题包（优先取安装前的 `.gctune-bak` 原始备份，没有备份则取当前 `.itz`）导出到 App 私有目录。
3. App 以它为**模板**重打包：模板的整个目录结构与它自己的 `description.xml` 原样保留，你的包里与模板同名的文件逐个覆盖进去（`description.xml` 一律用模板的）。
4. 生成 `theme-compat.itz` 后状态卡提示「兼容包已生成（替换模板里的 N 个文件，保留模板结构 M 项）」，再点 **安装并打开 iTheme**。

限制要清楚：只有**与模板同名**的文件才会被替换；如果你的包和模板资源命名完全不同，装上后外观变化会很小。此时更实际的做法是拿模板包当底包，按它的文件名把自己的素材替换进去再导入。

### 主题诊断与还原原主题

主题页在「安装并打开 iTheme」下方还有三个按钮，专门用来处理「装完主题后本地主题少了/没了」「iTheme 不认这个包」这类问题：

- **主题诊断**（只读）：把 `/data/bbkcore` 下的 `.itz` 清单（路径、大小、mtime、文件头 4 字节）、`.gctune-bak` 备份数量与前 10 个路径、`/data/bbkcore` 前 5 层目录树、以及 `logcat` 里 `itheme` / `theme` 相关尾行一次性取回，弹窗显示并可「复制文本」。它走同一条 uid 1000 通道（否则读不到 `/data/bbkcore`），所以仍需打开游戏模式，但**不写任何文件**。
- **还原原主题**：把所有 `*.gctune-bak` 覆盖回原文件。还原后请**重启 iTheme**（或先强制停止再打开）再看「本地主题」——iTheme 的主题列表是启动时加载的，不重启看不到变化。

关于 `.gctune-bak`：从 1.2.4 起，每次安装前都会为每个将被覆盖的 `.itz` 建一份同目录备份；1.2.5 起备份与还原都会保留原始的修改时间（`cp -p` / `touch -r`），这样即使还原也能对上 iTheme 记录里的下载时间。备份文件扩展名不是 `.itz`，不会被 iTheme 当成主题包；确认不再需要时，可以在文件管理器里手动删除以释放空间。

---

## 诊断与反馈（无需电脑）

GC-Tune 的每一项特权操作都走同一条通道：把命令写进系统 `settings`，由 Game Cube 以 `uid 1000`（system）执行，再把结果写回 `settings` 供 App 读取。只要这个来回没有回应，界面就会提示 **uid 1000 无响应**——它说明的是通道没回话，与字体文件、主题包、图标包本身无关。常见原因有三个：`WRITE_SECURE_SETTINGS` 没授予、GC Tune 不在电竞模式游戏列表、OriginOS 侧的钩子（SurfaceControl 图层 / ACK 广播）失效。

过去这类问题只能靠用户口头描述，最难拿到的恰恰是现场。从 1.2.3-zh 起，「设置」页内置 **诊断与反馈** 卡片，不需要电脑、不需要 ADB，就能把通道现场整理成一份纯文本报告。

### 四步操作

1. 打开 GC Tune &rarr; **设置**，找到卡片 **诊断与反馈**（在原有参数卡片下方，第一行「通道自检」、第二行「导出诊断日志」）。
2. 点 **通道自检**。App 会通过 uid 1000 执行一条只读检查命令：`id`、`ro.vivo.os.version`、`ro.build.display.id`、`/data/bbkcore` 与 `/data/vfonts` 是否存在、iTheme 相关包名、`current_game_package`；等待上限取设置页里的超时值（`timeout_sec`，默认 60 秒）。
3. 看结果。通道正常时对话框显示 **uid 1000 已正常响应** 与命令输出前若干行；超时则显示 **uid 1000 无响应**，并提示诊断日志已自动保存。
4. 点 **导出诊断日志**，在对话框里选 **分享文件**（系统分享面板，可直接发微信 / QQ）或 **复制文本**（全文进剪贴板），然后把文件或文本发给作者；对话框与设置页卡片底部都会显示这份日志的完整路径。

这些操作都不需要电脑。另外，任何命令超时或失败时 App 都会自动生成同一份日志，并在弹窗里提供 **发送给作者** 按钮，效果与手动导出相同。

### 日志保存位置

| 位置 | 路径 | 说明 |
| --- | --- | --- |
| 应用外部私有目录 | `/sdcard/Android/data/com.poc.gctune/files/diag/gctune-diag-<yyyymmdd-hhmmss>.txt` | 原始日志，通过 FileProvider 分享；文件名里的时间是生成时刻 |
| 公共「下载」目录 | `Download/gctune/diag/gctune-diag-<yyyymmdd-hhmmss>.txt` | Android 10 及以上额外复制一份，文件管理器里能直接找到 |

Android 11 起系统文件管理器对 `Android/data` 的限制变多，私有目录里找不到文件时，直接去 `Download/gctune/diag/` 取即可。

### 报告里有什么

报告是纯文本（UTF-8），按节组织，不含账号、聊天内容或照片数据：

- **报告头**：`GC Tune 诊断报告`、生成时间、报告格式版本 `diag-1`。
- **应用信息**：包名、versionName / versionCode、首次安装与更新时间、安装来源（ADB / 商店）、签名 SHA-256。
- **系统信息**：品牌 / 型号 / 设备 / 厂商、Android 版本与 SDK、安全补丁、ABI、内核、vivo / OriginOS 属性（`ro.vivo.os.version` 等）、ROM 指纹。
- **权限与后台**：`WRITE_SECURE_SETTINGS` 是否授予、通知权限、电池优化是否豁免、`ChannelService` / `KeepAliveService` 组件是否启用。
- **通道快照**：`settings secure gc_tune_cmd`（长度、预览、是否含守护进程引导标记）、`settings system gc_tune_ack`（原文与解析出的编号）、`settings system current_game_package`，以及偏好设置里的 `require_game` / `daemon_on` / `timeout_sec`。
- **自检判定**：逐条 ✓ / ✗ 结论与建议动作，例如缺 `WRITE_SECURE_SETTINGS` 时直接给出 adb 授权命令，图层创建失败或无 ACK 广播时判定为 OriginOS 侧钩子失效。
- **最近一次命令**：完整命令、耗时、结果（成功 / 超时）、输出摘要。
- **事件日志**：最近约 400 条带毫秒时间戳的事件（apply 各阶段、图层创建、ACK 广播、WAKE 重试、命令结束）。
- **结尾**：反馈模板（机型 / ROM 版本 / 是否在游戏列表 / 报告附上），以及一句说明——本文件不含隐私数据，只含诊断所需的环境与通道状态。

### 把日志发给作者

1. 点 **分享文件**，在系统分享面板里选微信 / QQ，直接发给作者；文件最完整，优先用这种方式。
2. 不方便发文件时点 **复制文本**，把全文粘贴到聊天窗口；事件日志较长，聊天软件可能截断，建议分条发送或改用分享文件。
3. 需要自己取文件时，按上面的路径在文件管理器里找；设置页卡片底部显示的「最近一份」就是这份文件的绝对路径。
4. 附上模板信息：**机型 / ROM 版本 / 是否在游戏列表 / 报告附上**。

能连电脑的用户仍可用仓库里的 `dist/gctune-diag.ps1` 在 adb 侧补充排查（字段更全），但它不是必需步骤，普通反馈走 App 内导出即可。

---

## 架构与 IPC 通道

```
 +-------------------------------------------------------+
 |                       GC Tune                         |
 |  com.poc.gctune (Activities / Foreground Service)      |
 +-------------------------------------------------------+
                             |
                   SurfaceControl / IPC
                             v
 +-------------------------------------------------------+
 |                 Game Cube Subsystem                   |
 |              SurfaceFlinger / uid 1000                |
 +-------------------------------------------------------+
                             |
         +-------------------+-------------------+
         |                   |                   |
         v                   v                   v
   System Shell        Theme Patcher        Icon Engine
 (uid 1000 execution)   (.itz rewrite)    (Launcher tint)
```

应用在命令执行期间启动一个轻量前台服务（`ChannelService`），由它持有图层与 ACK 接收器。监听守护进程严格由 `logcat` 事件驱动，空闲时 CPU 占用为零。

---

## 从源码构建

### 前置要求

- JDK 11 或更高版本
- Android SDK（Platform 35 / Build-Tools 34.0.0+）
- PowerShell（Windows / Linux / macOS）

### 构建步骤

克隆仓库并运行构建脚本：

```bash
git clone https://github.com/Bebrazui/GC-Tune.git
cd GC-Tune
.\\build.ps1
```

脚本使用 `javac` 编译 Java 源码，通过 `d8` 将 class 打包为 DEX，再用 `aapt` 打包 APK，最后使用 `apksigner` 完成签名。

---

## 免责声明

本项目仅供研究、设备定制与教学用途。修改系统参数前，请确保遵守设备厂商的相关政策。
