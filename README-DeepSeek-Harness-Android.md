# DeepSeek Harness Android APK

把 DeepSeek 开源的 Agent 框架 **DeepSeek Harness（dsh）v0.2.1-rc.2** 完整打包成了安卓 APK：内嵌 Node.js 26 运行时 + dsh 自包含运行时，用 WebView 承载本地 Web UI（127.0.0.1:3080）。**Agent 直接跑在手机上，可读写手机本地文件**。Android 移植包版本：**1.0.0**。

## 文件

| 文件 | 说明 |
|---|---|
| `DeepSeek-Harness-1.0.0-android-arm64.apk` | **可直接安装的 APK**（约 121 MiB，arm64-v8a，自包含全部运行时） |
| `dsh-android/` | Android 工程源码（Kotlin，`gradle assembleRelease` 可复现本 APK） |

## 1.0.0 发布说明

首个正式版，累计修复（按发现顺序）：

| 能力 | 状态 |
|---|---|
| 内嵌 Node 26 + dsh 运行时，arm64 设备 | 完整打包，侧载即用 |
| 执行私有目录可执行文件（Android 10+ W^X 限制） | targetSdk 28，与 Termux 同方案 |
| Android 原生绑定（node-addon-require-builtin） | NDK 交叉编译内置 |
| 终端（node-pty / 进程检查 / flock） | 交叉编译 + android 平台适配 |
| 会话持久化、新建文件、附件上传的原子发布 | Android `link(2)` 限制已绕过（自动回退重命名/复制） |
| 手机网络 TLS 中间人（路由器/安全 App 拦证书） | 三层自动修复 + 启动自检 + 全链错误日志 |
| 界面 | 头部一行公告 + Web 全屏；公告连点 10 次出运行日志（可一键复制） |

## 安装

1. 传 APK 到手机（数据线/微信文件传输/网盘均可），点击安装（需允许"安装未知来源应用"）。
2. **仅支持 arm64（64 位 ARM）手机**——2017 年后的机型基本都是；极少数 32 位老机型无法运行。
3. 首次启动会解压约 400MB 运行时到应用私有目录，需要 1-3 分钟，请保持前台等待。

## 首次启动流程

1. 应用请求通知权限（用于前台服务常驻通知）。
2. 解压运行时（进度实时显示，约 1-3 分钟）。
3. 存储授权（让 Agent **直接操作手机文件**的关键）：
   - 点 **"授权访问全部文件"** 按钮 → 先弹系统运行时权限对话框（"允许访问照片、媒体和文件"），选**允许**即可；
   - 若对话框被拒或个别 ROM 静默拒绝，应用会自动跳转系统设置的 **"所有文件管理权限"** 页，打开开关后返回，服务自动重启并切换到共享存储。
   - 授权后 Agent 可读写 `/sdcard` 下的照片、下载、文档等；默认工作目录：`/sdcard/dsh-workspace`（Agent 操作相对路径时的根目录）。
   - 不授权也能用，但只能访问应用私有目录。
4. 服务自动启动，WebView 加载带 token 的本地地址（token 由服务自动处理，无需手动复制）。

> **关于 targetSdk 28**：Android 10 起的 W^X 安全策略禁止 targetSdk ≥ 29 的应用执行自身私有目录里的可执行文件（否则报 `error=13, Permission denied`）。本 APK 与 Termux 采用同一方案：targetSdk 保持 28，从而允许执行内嵌 Node，同时自动获得 legacy 外部存储访问能力。

## 使用

- **界面（v10 起）**：头部只有一行公告"点个 Star 支持 · GitHub"——点 **GitHub** 跳系统浏览器打开仓库；其余空间全部由 dsh Web UI 填满，与桌面版完全一致：在设置里填入 DeepSeek API Key（或配置其他模型提供方），即可开始对话、建任务、让它读写文件、跑命令。
- **运行日志**：默认隐藏。在头部公告文本上**连点 10 次**，页面下方滑出运行日志面板（再连点 10 次收起）；日志标题右侧有**复制日志**按钮，点击即复制全文到剪贴板，日志内容也支持长按选择复制。解压/启动阶段的状态会临时显示在公告行，服务就绪后自动恢复公告。
- 服务以前台通知形式常驻，锁屏后继续运行；点通知可回到应用。
- WebView 内可调试（chrome://inspect）。

## 故障排查

| 现象 | 处理 |
|---|---|
| `启动失败: ... error=13, Permission denied` | 旧版 APK 的 targetSdk 问题，已修复（targetSdk 28）；请安装当前版本 |
| `服务进程退出 (code=1)`，日志有 `No usable native binding found for node-addon-require-builtin-android-arm64` | 旧版 APK 缺少 Android 原生绑定（dsh 官方未提供 android-arm64 预编译包），已在当前版本修复：从官方源码用 NDK 交叉编译内置 |
| **`会话失败 … flock is not supported on android-arm64`** | 旧版 APK 的 `node-addon-system` 在 Android 上无原生 flock 绑定会直接抛错；已修复：`flock.js` 在 Android 上改为 no-op（单进程运行时本就不需要跨进程锁，与 dsh 自带的 worker 态 no-op 方案一致） |
| **`本轮运行失败 EACCES: permission denied, link '…session.v4.jsonl.zstd…tmp' -> …`** | 会话持久化在**创建当前会话文件**时用 `fs.link`（硬链接）做原子发布，而 Android 的 `link(2)` 系统调用返回 `EACCES`（Android 对硬链接有安全限制，即便在应用私有目录）。已修复：`materializePosix`（主路径）与 `publishCurrentExclusive`（迁移路径）在硬链接失败（EACCES/EPERM/EXDEV/ENOSYS 等）时都会自动回退到 `fs.rename` 原子重命名，语义等价且对所有文件系统（含 `/sdcard` 的 vfat）都可工作 |
| **`新建终端报错 subprocess-local: terminal inspection is unsupported on platform android`** | 终端子进程插件在加载时按 `process.platform` 选择进程检查器，而本机内嵌 Node 在 Android 上返回 `android`（不是 `linux`），导致该插件直接拒绝加载。已修复：在 `createProcessInspector` 中把 `android` 与 `linux` 同等处理（Android 本质就是带 `/proc` 的 Linux，且已内置 arm64 syscall 表，进程就绪/信号/回收逻辑完全适用） |
| **`终端错误 Failed to load native module: pty.node … prebuilds/android-arm64`** | 终端依赖 `node-pty` 的 android-arm64 原生绑定。已修复：① 用 NDK 交叉编译 `pty.node`（NAPI 模块，forkpty 来自 bionic libc），同时放进 `build/Release/` 与 node-pty 标准的 `prebuilds/android-arm64/`；② 默认 shell 显式设为 `/system/bin/sh`，避免继承 Termux 路径导致无法打开 |
| 启动后 Agent 看不到手机文件 | 点"授权访问全部文件"完成存储授权；授权后服务会自动重启 |
| **运行日志反复 `[deepseek-account] request failed { errorCode: 'no-response' }` / `sign-in failed`** | 这是 Node 的 `fetch` 访问 DeepSeek 服务器时**抛了异常**（网络层失败，不是密码/授权错误）。已确认：内嵌 Node 自带根证书、同版本 Node 实测能正常连 `api.deepseek.com`，所以**绝大多数是手机网络问题**——请先确认手机能正常上网（浏览器打开任意网页）。从本版本起日志会打印真实错误（`errName`/`errMessage`/`causeCode`，如 `ENOTFOUND`=DNS、`ETIMEDOUT`=连不上、`ECONNREFUSED`=被挡），据此可精准定位。另：账号自动登录是可选功能，**直接填 API Key 即可使用**，不受影响 |
| **`本轮运行失败 DeepSeek Messages transport failed` / 添加 API Key 也失败** | 同样是内嵌 Node 的 `fetch` 失败（LLM 请求发不出去）。v8 起**启动约 5 秒后会自动做一次网络自检**，并在任何 deepseek 请求失败时把自检结果一起打进日志（`[dsh-net]` 前缀）：`interfaces`（网卡/IP）、`dns servers`、逐级 `dns ok/FAILED`（域名解析）、`tcp ok/FAILED`（IPv4 连 443 端口）、`fetch ok/FAILED`（完整 TLS+HTTP）。对照即可定位断在哪一级：`dns FAILED`=手机 DNS 问题（可换 DNS 823.5.5.5 或换网）；`tcp FAILED`=网络不可达/被挡；`fetch FAILED` 且 `tcp ok`=TLS/证书问题（检查手机系统时间是否准确）；全部 `ok` 则是当时网络瞬时故障，重试即可 |
| **新建文件报 `cannot write "...": EACCES: permission denied, link ...`（改已有文件却正常）** | Android 内核/SELinux 禁止应用进程使用 `link(2)` 硬链接（即便在应用自己的私有目录），而写入工具新建文件时正是用硬链接做原子发布。v11 已修复：link 失败（EACCES/EPERM/EXDEV/ENOSYS 等）时自动回退为同目录原子重命名，并保留"目标已存在则不覆盖"的语义；附件上传的同类 link 发布同样已修复（别名用复制、暂存用重命名）。若日志里出现 `mkdir '/data/local/tmp/...': EACCES`，那是 Agent 在探测 `/data/local/tmp`——该目录按系统设计就不允许应用写，属正常现象，Agent 会换其他位置 |
| **`fetch FAILED` 且 `code: SELF_SIGNED_CERT_IN_CHAIN` / `UNABLE_TO_GET_ISSUER_CERT_LOCALLY`（所有 https 都失败，但浏览器正常）** | 手机网络存在 **TLS 中间人**（路由器/运营商/安全类 App 拦截并替换证书）：浏览器不报警是因为 Android 系统信任了该根证书，而内嵌 Node 只认自带的 118 个根证书。v9 已自动修复：① 启动时把 Android 各 CA 存储（`/system/etc/security/cacerts`、apex、用户存储）合并进 Node 信任库（`NODE_EXTRA_CA_CERTS`）；② 若仍报证书错误，自检会用不安全探测取回对方证书链，**自动学习链最深处那个自签名根证书**并信任（日志 `tls learn: root trusted for this session`），同时持久化，下次冷启动直接生效。无需任何手动操作；若学习日志显示 `deepest cert is not a self-signed root`，说明该网络没把根证书发出来，换网络（如切到手机数据）或关闭路由器/安全类 App 的 HTTPS 拦截即可 |
| 运行日志面板无法复制文本 | 在头部公告上**连点 10 次**滑出日志面板；标题右侧有**复制日志**按钮，点击即复制全文到剪贴板，内容也支持长按选择复制 |
| 提示"Node 可执行文件不可执行" | 个别 ROM umask 问题：清除应用数据后重试，或卸载重装 |
| 首次解压后白屏 | 锁屏/杀后台导致服务被杀：重新打开应用即可 |

## 已知限制（预览版裁剪）

为控制 APK 体积，以下依赖平台原生组件的功能被裁剪，在 Android 上本就不可用：

| 功能 | 状态 |
|---|---|
| 文件编辑 / 搜索 / Agent 核心循环 / Web UI | ✅ 完整可用 |
| bash 工具（`/system/bin/sh`） | ✅ 可用 |
| 终端面板（node-pty，已交叉编译 android-arm64 绑定） | ✅ 已恢复 |
| Office 转 PDF（LibreOffice wasm，146MB） | ❌ 已移除 |
| 语音转文字（sherpa-onnx） | ❌ 已移除 |
| 图片处理（sharp libvips） | ❌ 已移除 |
| Inspector 调试面板 | ❌ 已移除 |
| 32 位 ARM 设备 | ❌ 不支持 |

## 重新打包（如需修改）

```bash
cd /workspace/dsh-android
export ANDROID_HOME=/opt/android-sdk
/opt/gradle-8.14.3/bin/gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

该工程是**自包含**的：`app/src/main/assets/dsh-payload.zip`（约 115 MiB）里已经打包了
Node 26.12.1（aarch64）+ 全部依赖库 + dsh 自包含运行时（含从官方源码交叉编译的
`node-addon-require-builtin-android-arm64` 原生绑定），直接 `gradle assembleRelease`
即可复现本 APK，无需再准备其他目录。

## 技术架构

```
APK (126MB)
├── classes.dex            Kotlin 代码（WebView 容器 / 前台服务 / 解压器）
└── assets/dsh-payload.zip (121MB, 已压缩)
    ├── node/              Node 26 aarch64 + libc++/openssl/icu 等 11 个依赖库
    └── dsh/               dsh 自包含运行时（约 30556 个文件 + 2640 个符号链接）
        ├── lib/bin.js     dsh CLI 入口（dsh web）
        └── node_modules/  pnpm 布局（含 @deepseek-ai/* 等 350+ 包）
            ├── .pnpm/node-addon-require-builtin-android-arm64/  ← 本次交叉编译补入
            └── .pnpm/node-pty@1.2.0-beta.15/                     ← 本次交叉编译补入
```

启动链：MainActivity → 解压 payload → DshService（前台服务）→ `node lib/bin.js web --no-open --port 3080`
→ 解析 stdout 中的 `http://127.0.0.1:3080/?token=...` → WebView 加载 → 303 设置 cookie → 进入 UI。

**关于 android-arm64 原生绑定**：dsh 的模块解析器需要通过 `node-addon-require-builtin`
读取 Node 内部模块（`internal/modules/cjs/loader` 等），官方 npm 只提供桌面平台预编译包。
本 APK 内的绑定是从官方源码（`deepseek-ai/dsh-node-addon-require-builtin`）用 Android NDK
交叉编译的 N-API 版本，已通过 qemu + Node 26.12.1 aarch64 实测验证。

**关于终端（node-pty）**：dsh 的终端面板依赖 `node-pty` 的原生 PTY 绑定（forkpty），
官方同样只提供桌面预编译包，且运行时裁剪阶段被误删。本 APK 已用 Android NDK 交叉编译
`node-pty@1.2.0-beta.15` 的 `pty.node`（forkpty 来自 bionic libc，无需额外库），并还原进
pnpm 布局；同时把默认 shell 显式设为 `/system/bin/sh`，避免设备继承了 Termux 的
`/data/data/com.termux/.../bash` 路径（本应用无权限访问）导致“打开终端失败”。
