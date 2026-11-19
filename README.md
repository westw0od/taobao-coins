# 淘宝淘金币自动任务

通过 USB 调试连接 Android 手机，自动完成大部分常见的淘宝站内免费淘金币任务。程序使用实时截图识别任务和按钮，持续轻滑激活浏览计时，返回面板检查任务进度。

当前版本聚焦淘金币。任务入口、次数和奖励会随账号、日期及淘宝版本变化，实际执行范围取决于当天页面可识别的任务。

## 支持范围

| 任务 | 当前行为 |
| --- | --- |
| 每日签到 | 识别“签到领金币”，签到后进入“赚更多金币” |
| 任务到访奖励 | 识别并领取任务面板中的到访奖励 |
| 免费浏览、逛清单、看好物 | 持续滑动，检测到账或完成提示后提前返回 |
| 搜索浏览 | 选择当前搜索建议，浏览结果并核对任务进度 |
| 点击 3 个商品 | 打开任务内指定商品详情，返回检查次数 |
| 每日答题 | 已知题直接作答，未知题按 A、B、C 尝试，页面提供重答入口时继续 |
| 带购物推广的免费浏览 | 仅执行明确标注浏览秒数的奖励部分 |

跨 App、游戏、分享邀请、付款、确认收货、会员开通及桌面组件等任务会留在列表中。红包签到和元宝任务已从程序中移除。

任务标题和整行描述都会参与过滤。若某个新任务意外打开微信、支付宝等外部应用，程序会结束本项操作，尝试返回淘宝，并在本轮保留该任务。

## 环境准备

- 一台安装 Python 的电脑。建议使用 Python 3.10，既有实机适配环境为 Windows、Python 3.10 和三星 S24。
- 一部已安装并登录淘宝的 Android 手机。
- 一条支持数据传输的 USB 数据线。
- Android SDK Platform Tools 中的 `adb`。

下载 Platform Tools 后，将其目录加入 `PATH`。Windows 用户也可以将解压得到的 `platform-tools` 文件夹放到本项目根目录，程序会自动查找其中的 `adb.exe`。官方安装包见 Android 开发者网站。[下载 Platform Tools](https://developer.android.com/tools/releases/platform-tools)

手机开启“开发者选项 → USB 调试”，连接电脑并允许 USB 调试授权。运行期间保持手机解锁，提前完成淘宝登录。

在终端检查连接。

```powershell
adb devices
```

设备状态为 `device` 即可继续。`unauthorized` 表示需要在手机上允许授权，`offline` 或空列表需要检查数据线和 USB 调试连接。

## 安装

下载仓库 ZIP 并解压，或使用 Git 克隆。

```powershell
git clone https://github.com/westw0od/taobao-coins.git
cd taobao-coins
```

Windows 首次安装运行。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\setup.ps1
```

脚本会在项目内创建 `.venv` 并安装 `requirements.txt` 中的依赖。

如果当前网络访问 PyPI 超时或出现 TLS 连接错误，可以通过 HTTPS 镜像安装依赖。

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

也可以手动安装。

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

## 使用

Windows 用户双击 `start.cmd` 即可开始。程序只运行金币任务。

首次使用可以先检查设备连接。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1 -Check
```

从终端启动任务。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
```

ADB 未加入 `PATH` 时显式指定路径。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1 -Adb "C:\Android\platform-tools\adb.exe"
```

连接多部手机时，通过 `adb devices` 查看编号后选择一台。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1 -Serial "你的设备编号"
```

Python 命令行提供相同参数。

```powershell
.\.venv\Scripts\python.exe main.py --help
.\.venv\Scripts\python.exe main.py --check
.\.venv\Scripts\python.exe main.py --serial "你的设备编号" --adb "C:\Android\platform-tools\adb.exe"
```

Linux 和 macOS 可以使用系统的 `adb` 与 Python 虚拟环境运行同一 Python 入口，这两个平台的运行情况仍需实机反馈。

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python main.py --check
.venv/bin/python main.py
```

淘宝安装在工作资料或其他安卓用户中时，可以传入 `--android-user 用户ID`。默认值为 `0`，对应主用户。

## 程序如何完成任务

1. 检查设备连接并进入淘宝，识别签到入口和金币任务面板。
2. 使用实时截图 OCR 读取当前任务，过滤跨 App、游戏和需要额外操作的任务。
3. 进入免费任务，按页面内容搜索、点击商品或持续轻滑。
4. 浏览期间定期识别顶部和右侧的“金币已到账”“任务已完成”等提示，确认完成后提前返回。
5. 返回任务面板，用新的识别结果检查进度并选择下一项。
6. 同一任务状态最多尝试两次，扫描后保存运行结果。

完成提示识别失败时，程序会使用该任务的等待时长兜底，再回面板检查进度。“进度已更新”表示任务列表发生了变化，到账金额以手机实际显示为准。

## 常见问题

### 窗口关掉后还会运行吗

关闭运行窗口会结束程序。保持窗口打开或最小化即可，主动停止可以按 `Ctrl+C`。

### 遇到滑块验证或登录页面怎么办

识别到验证后程序会停止并提示手动处理。在手机上完成验证或登录，再启动程序。界面改版可能影响验证文字识别，出现这类页面时也可以主动停止程序后处理。

### 浏览页面显示完成，为什么还没有返回

程序会定期检查状态提示，检查和页面导航需要数秒。部分活动把提示放在不同位置，或用新的文字表示完成，可能触发等待时长兜底。可以提供该页面的截图和任务名称，帮助适配识别位置。

### 程序打开外部应用怎么办

程序会在页面操作前检查前台应用，任务意外跳到外部应用时尝试恢复淘宝。手动切换手机应用也会影响正在执行的任务，建议在运行期间让手机供程序使用。外部应用中的浏览不会计为淘宝任务完成。

### 还有任务留着，是没做完吗

列表中可能包含被过滤的任务、已尝试两次仍未计数的任务，或当前版本暂时无法识别的任务。金币任务面板仍可手动查看和操作。

### 提示另一个程序正在运行怎么办

同一项目目录同时运行一份程序。关闭已有窗口后再启动即可，进程退出时系统会释放文件锁。

### 报错后在哪里找日志

每轮运行会生成独立目录。

```text
logs/
└── coins-日期-时间/
    ├── run.log
    └── result.json
```

运行截图仅在内存中用于 OCR，识别后释放图像资源。程序在启动和结束时自动清理 `logs` 中的历史截图，正常结束、可捕获异常和 Ctrl+C 都会执行结束清理。日志和 JSON 运行结果继续保存。清理失败会在日志和结果中列出，成功运行遇到清理失败时返回非零退出码。

USB 断开后重新连接、授权，再启动程序。

提交 Issue 时，请提供任务名称、错误信息、手机型号、屏幕分辨率和淘宝版本。截图、日志可能包含个人信息，请先处理这些内容。运行记录和虚拟环境已加入 `.gitignore`。

## 代码结构

```text
main.py                 命令行、单实例锁、日志和运行结果
taobao_coins/
├── android.py          设备选择、前台检查和淘宝恢复
├── screen.py           OCR、任务过滤和完成提示识别
└── runner.py           导航、浏览、搜索、商品点击和答题
tests/                  任务过滤与执行流程回归检查
setup.ps1               Windows 依赖安装
run.ps1                 Windows 启动脚本
start.cmd               Windows 双击入口
```

运行回归检查。

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

本仓库的自动化检查覆盖任务过滤、应用切换、完成提示、等待时长和答题重试等逻辑。不同设备和淘宝活动页面的实际兼容性需要持续实机验证。

## 来源与许可证

本项目基于 `czl0325/coin11-tb` 的任务执行思路和部分代码整理，保留 Apache-2.0 许可证，并增加了当前金币工作流、OCR 识别、整行任务过滤、完成检测和通用启动入口。具体改动来源见 `NOTICE`。[原项目](https://github.com/czl0325/coin11-tb)

项目按 Apache-2.0 许可证公开，欢迎提交 Issue 和改进。[许可证](LICENSE)
