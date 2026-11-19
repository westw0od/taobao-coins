<div align="center">

<h1>🪙 Taobao Coins</h1>
<p>连接安卓手机，自动完成淘宝站内免费淘金币任务。</p>
<p><b>签到 · 浏览 · 搜索 · 商品点击 · 答题</b></p>
<p>
  <a href="#快速开始">快速开始</a> ·
  <a href="https://github.com/westw0od/taobao-coins/archive/refs/heads/main.zip">下载代码</a> ·
  <a href="https://github.com/westw0od/taobao-coins/issues">反馈问题</a>
</p>

</div>

持续滑动激活浏览计时，识别到任务完成后自动返回。支持大部分常见的站内免费金币任务，具体任务和奖励以当天页面为准。

跨 App、游戏、付款、分享邀请、会员开通等任务会保留在列表中。当前版本只运行淘金币任务。

## 快速开始

**准备好电脑和手机**

安装 Python，建议使用 3.10。手机登录淘宝，开启 **USB 调试**，连接数据线并允许电脑授权。

下载 Platform Tools，将其目录加入 `PATH`，或把解压后的 `platform-tools` 文件夹放到项目根目录。[下载 ADB](https://developer.android.com/tools/releases/platform-tools)

**安装依赖**

下载并解压代码后，在项目目录打开 PowerShell，运行一次安装脚本。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\setup.ps1
```

**双击 `start.cmd` 开始。** 运行时保持手机解锁、淘宝已登录，窗口可最小化。按 `Ctrl+C` 或关闭窗口结束运行。

<details>
<summary>安装失败？使用 HTTPS 镜像重试</summary>

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

</details>

## 更多用法

<details>
<summary>检查连接、指定手机或 ADB 路径</summary>

```powershell
# 检查设备连接
.\.venv\Scripts\python.exe main.py --check

# 指定 ADB 路径
.\.venv\Scripts\python.exe main.py --adb "C:\Android\platform-tools\adb.exe"

# 多部手机连接时，指定 adb devices 中的设备编号
.\.venv\Scripts\python.exe main.py --serial "设备编号"

# 查看全部参数
.\.venv\Scripts\python.exe main.py --help
```

淘宝安装在其他安卓用户中时，使用 `--android-user 用户ID`，默认值为 `0`。

</details>

<details>
<summary>Linux / macOS</summary>

安装 Python 和 ADB 后运行。两个平台仍需更多实机反馈。

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python main.py --check
.venv/bin/python main.py
```

</details>

<details>
<summary>常见问题</summary>

| 情况 | 处理方式 |
| --- | --- |
| 手机连接失败 | 运行 `adb devices`，状态应为 `device`。`unauthorized` 需在手机授权，`offline` 或空列表需检查数据线与 USB 调试 |
| 出现滑块或登录验证 | 手动完成后重新启动程序 |
| 完成后仍停留数秒 | 程序定期检查完成提示；提示识别失败时按任务等待时长兜底，再核对进度 |
| 意外打开外部 App | 程序尝试返回淘宝，本轮保留该任务 |
| 部分任务没有完成 | 可能被过滤、暂未适配，或尝试两次仍未计数，可在手机上手动检查 |
| 提示已有程序运行 | 关闭之前的运行窗口，再启动一份程序 |

**运行记录**保存在 `logs/每轮运行目录/`，包含 `run.log` 和 `result.json`。“进度已更新”表示面板发生变化，到账金额以手机实际显示为准。

**截图**仅在内存中识别，启动和结束时自动清理日志目录中的历史截图。清理失败会写入运行记录。

反馈问题时附上任务名称、日志、手机型号和淘宝版本，并隐藏个人信息。

</details>

<details>
<summary>代码结构与回归检查</summary>

```text
main.py                  启动、日志、文件锁和截图清理
taobao_coins/
├── android.py           设备连接与淘宝恢复
├── screen.py            页面识别与任务过滤
└── runner.py            浏览、搜索、商品点击和答题
```

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

已验证的开发环境为 Windows、Python 3.10；既有任务逻辑基于三星 S24 适配。不同设备和淘宝页面的兼容性仍需实机验证。

</details>

---

基于 czl0325/coin11-tb 整理，按 Apache-2.0 许可证公开。[原项目](https://github.com/czl0325/coin11-tb) · [来源说明](NOTICE) · [许可证](LICENSE)
