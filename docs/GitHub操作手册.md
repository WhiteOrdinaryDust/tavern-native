# GitHub 操作手册（Agent 版）

> 适用环境：Windows + 无本地代理 + 国内宽带/手机热点 + 任意项目
> 目标：让 agent 能**自己诊断、自己选路、自己推送、自己验收**
> 来源：2026-10 在 `tavern-native` 项目上实测总结（含 6 个真实踩坑）

---

## 0. TL;DR

| 情况 | 走哪条路 |
|---|---|
| 长期使用（推荐，一次配置） | **SSH over 443**（第 2 节）→ 之后宽带直接可推 |
| 临时救急 | 手机热点（第 3 节）→ 多数情况立即可推 |
| 给 agent 用的**不要**选 | GCM 设备码（第 4 节）→ 非交互环境抓不到设备码 |
| 完全连不上 GitHub 且无法开热点 | PAT 或国内托管（第 5 节） |

**必须先诊断（第 1 节）**，不要凭猜测选方案。

---

## 1. 先诊断（四条命令，10 秒）

```powershell
# ① HTTPS 是否可用（决定 git push 能否走 HTTPS）
foreach ($u in @('https://github.com','https://api.github.com','https://codeload.github.com')) {
  "$u -> HTTP " + (& curl.exe -s -o NUL -w "%{http_code}" --max-time 10 $u)
}
# ② SSH 备用通道是否可用（关键！常与 ① 的结论相反）
Test-NetConnection ssh.github.com -Port 443 -InformationLevel Quiet
# ③ 有没有本地代理（有就直接配给 git）
foreach ($p in @(7890,7891,7897,10809,10808,1080,8118,8889)) {
  if (Test-NetConnection 127.0.0.1 -Port $p -InformationLevel Quiet) { "代理端口: $p" }
}
# ④ 系统代理设置
Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' | Select ProxyEnable,ProxyServer
```

典型结果与结论：

| 现象 | 含义 | 该怎么办 |
|---|---|---|
| `github.com → 000`，`ssh.github.com:443 → 可连` | 主站被干扰，备用通道可用 | **走 SSH over 443（第 2 节）** |
| `github.com → 200` | 直连可用 | 直接用 HTTPS（或仍推荐 SSH，免登录） |
| 有本地代理端口 | 有代理 | `git config --global http.proxy http://127.0.0.1:<port>` 后走 HTTPS |
| 全部不可用、`gitee.com → 200` | GitHub 不可达 | 国内托管（第 5 节）或开热点 |

> ⚠️ `Test-NetConnection` 的 TCP 结果**不可靠**（会被安全软件干扰）；以 `curl` 的 HTTP 码为准，`000` = 实际握手失败。

---

## 2. 首选：SSH over 443（一次配置，长期有效）

原理：把 `github.com` 在 SSH 层**改道**到备用主机 `ssh.github.com:443`（不同 IP，通常没被干扰）。

### 2.1 生成密钥

```powershell
$key = "$env:USERPROFILE\.ssh\id_ed25519_github"
ssh-keygen -t ed25519 -N '""' -C "agent@$env:COMPUTERNAME" -f $key
```

### 2.2 写 `~/.ssh/config`

**必须无 BOM**（否则 SSH 直接拒绝整个配置 → git 谎报 "Repository not found"）：

```python
# 用 Python 写，绝不用 PowerShell 的 utf8/Add-Content
import pathlib, os
cfg = pathlib.Path.home() / ".ssh" / "config"
key = (pathlib.Path.home() / ".ssh" / "id_ed25519_github").as_posix()
block = f"""
# --- github over 443 ---
Host github.com
    HostName ssh.github.com
    Port 443
    User git
    IdentityFile {key}
    IdentitiesOnly yes
    StrictHostKeyChecking accept-new
# --- end ---
"""
old = cfg.read_text(encoding="utf-8", errors="ignore") if cfg.exists() else ""
if "github over 443" not in old:
    cfg.write_text((old.rstrip() + "\n" + block).lstrip("\n"), encoding="utf-8", newline="\n")
# 顺手去掉可能存在的 BOM
raw = cfg.read_bytes()
if raw[:3] == b"\xef\xbb\xbf":
    cfg.write_bytes(raw[3:])
```

### 2.3 把公钥加到 GitHub（需要用户操作一次）

1. 打印 `~/.ssh/id_ed25519_github.pub` 的**整行**内容给用户
2. 让用户打开 `https://github.com/settings/ssh/new`
   - Title 随意；**Key type = Authentication Key**；粘贴 → Add SSH key
3. 用户浏览器打不开 GitHub 时才需要考虑第 3/5 节

### 2.4 验证并切换远程

```powershell
ssh -o BatchMode=yes -T git@github.com      # 期望: Hi <用户名>! You've successfully authenticated
git remote set-url origin git@github.com:<owner>/<repo>.git
git ls-remote origin refs/heads/main         # 通了再推
```

### 2.5 常见失败对照

| 报错 | 真正原因 | 修法 |
|---|---|---|
| `Bad configuration option: \357\273\277#` | `config` 有 BOM | 用 Python 去掉前 3 字节（2.2 节末尾） |
| `Repository not found` + `and the repository exists` | ① 配置被 BOM 弄坏 ② 仓库名/owner 写错 ③ 密钥没加 | 逐个排除；先用 `ssh -T` 确认身份 |
| `Permission denied (publickey)` | 公钥还没加 / 加错账号 | 让用户重新粘贴公钥 |
| `Hi <别人的用户名>!` | 用错了密钥账号 | 检查 `IdentityFile` 与 `IdentitiesOnly yes` |

---

## 3. 备选：手机热点（临时救急）

```powershell
# 让用户把电脑连到手机热点后，重测
& curl.exe -s -o NUL -w "%{http_code}" --max-time 12 https://github.com
```
- 实测：**宽带 `000` → 热点 `200`**（不同运营商国际出口差异极大）
- 推送时**加循环重试**（热点会抖）：

```powershell
for ($i=1; $i -le 8; $i++) {
  $o = & git push origin main 2>&1
  if ($LASTEXITCODE -eq 0 -or (($o -join ' ') -match 'main -> main|up-to-date')) { "✓ 已推送"; break }
  Start-Sleep -Seconds 15
}
```

> ⚠️ Windows 热点在锁屏/省电时会自动关闭 → 建议关掉热点省电模式，或用 USB 共享网络。

---

## 4. 不推荐：GCM 设备码（给 agent 的警告）

`git config --global credential.gitHubAuthModes device` + HTTPS 推送时，GCM 会：
- 弹**图形窗口**或把**设备码写到控制台/TTY**，agent 的 stdout **抓不到** ✗
- 后台任务被杀 → 用户正在点的窗口**一起消失** ✗

结论：**非交互环境优先 SSH**（第 2 节）。若必须用设备码，让**用户自己**在终端执行一次 `git push` 完成授权。

---

## 5. 其他路线

### 5.1 PAT（最快，但令牌会进对话记录）

```powershell
git remote set-url origin https://<user>:<token>@github.com/<owner>/<repo>.git
```
- 建议：**fine-grained token、只给这一个仓库、Contents: Read/Write**
- 用完让用户**立刻撤销**；或改用 GCM 存一次凭据

### 5.2 国内托管（GitHub 完全不可达时）

`gitee.com` / `gitcode.com` 通常直连可用 ✓
- 建空仓库（**不要勾初始化**，否则首推冲突）
- Gitee 需要「私人令牌」（设置 → 安全设置 → 私人令牌 → 勾 `projects`）
- 注意 Gitee 对公开仓库有**实名 + 内容审核**
- 之后想加 GitHub 可做**双远程**：
```powershell
git remote set-url --add --push origin <gitee地址>
git remote set-url --add --push origin <github地址>
```

---

## 6. 推送与发版流程（tag → CI → Release）

```powershell
git add -A; git commit -m "..."; git push origin main
git tag -a v1.2.3 -m "v1.2.3: 说明"; git push origin v1.2.3
```

**关键点：**

| 事项 | 说明 |
|---|---|
| tag 必须指向**含新 workflow 的提交** | tag 触发的 CI 用**该 tag 提交**里的 workflow 文件 |
| 删了远程 tag 再推同名 tag，若本地 tag 还在旧提交 | **不会移动** → 必须 `git tag -f -a vX.Y.Z -m "..."` |
| `git push` 把进度写 **stderr** | PowerShell 会误报 `exit 1` → **看输出内容**（`main -> main` 即成功） |
| 打 tag 前确认 `versionCode`/`versionName` 已改 | 否则 Release 与包内容不一致 |
| CI 创建 Release 需要写权限 | workflow 里加 `permissions: { contents: write }` |

CI 出包最小可用片段（Android 示例）：

```yaml
name: release
on:
  push:
    branches: [main]
    tags: ["v*"]
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: write          # 不写 → 无法创建 Release
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - run: chmod +x ./gradlew        # Windows 提交的 gradlew 没有可执行位
      - name: 补齐 Android SDK 组件
        run: |
          yes | sdkmanager --licenses > /dev/null 2>&1 || true
          sdkmanager "platforms;android-36" || true
      - run: ./gradlew :domain:test
      - run: ./gradlew :app:assembleRelease
      - name: 定位并规范产物
        run: |
          mkdir -p dist
          APK=$(find app/build/outputs -name "*.apk" | head -1)
          [ -n "$APK" ] || { echo "没找到 APK"; exit 1; }
          cp "$APK" dist/app-release.apk
          ls -l dist/; sha256sum dist/app-release.apk
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: apk, path: dist/app-release.apk, if-no-files-found: error }
      - uses: softprops/action-gh-release@v2
        if: startsWith(github.ref, 'refs/tags/')
        with:
          files: dist/app-release.apk
          fail_on_unmatched_files: true
```

---

## 7. 产物验收（**必须做**，否则可能验收了个半包）

```powershell
# ① 先从 API 拿"官方声明"的大小与摘要
$j = (& curl.exe -s "https://api.github.com/repos/<owner>/<repo>/releases/tags/v1.2.3" | ConvertFrom-Json)
$j.assets | Select name, size, digest
# ② 下载（给足时间 + 重试）
& curl.exe -s -L --retry 5 --retry-delay 3 --retry-all-errors --max-time 900 `
  -o "$env:TEMP\chk.apk" "<browser_download_url>"
# ③ 比对大小与哈希（这一步才是验收）
(Get-Item "$env:TEMP\chk.apk").Length      # 必须 == 声明的 size
(Get-FileHash "$env:TEMP\chk.apk" -Algorithm SHA256).Hash.ToLower()   # 必须 == digest
# ④ 再用本机工具确认版本号与签名
$bt = "$env:USERPROFILE\Android\sdk\build-tools\35.0.0"
& "$bt\aapt.exe" dump badging "$env:TEMP\chk.apk" | Select-String '^package:'
$env:JAVA_HOME = "$env:USERPROFILE\java\17.0.13+11"     # apksigner 需要 JAVA_HOME！
& "$bt\apksigner.bat" verify --print-certs "$env:TEMP\chk.apk"
```

**两个必知陷阱：**
1. `curl --max-time` 到点会**截断**下载，但**仍打印 HTTP 200** → 只看状态码会误判 ✓
2. `aapt`/`apksigner` 对**中文路径**可能读不出 → 下载到纯英文临时路径

---

## 8. 公开签名密钥模型（可选，用于"任何人都能构建可覆盖安装的包"）

要点：
- 把 keystore + 密码放进仓库目录（如 `signing/`）+ 写**风险提示**
- Gradle 读配置时**回退**到公开副本：
```kotlin
val props = Properties().apply {
    val local = rootProject.file("keystore.properties")
    val published = rootProject.file("signing/keystore.properties")
    when { local.exists() -> local.inputStream().use { load(it) }
           published.exists() -> published.inputStream().use { load(it) } }
}
```
- `.gitignore` 只忽略**根目录**私有副本：写 `/keystore.properties`（**不要**写 `keystore.properties` ✗ 它会连 `signing/` 里的也忽略 → CI 构建出**未签名**包）
- 许可证建议 **GPL-3.0**（衍生必须开源）；README 里明确"任何人都能签名同包名包"

---

## 9. 坑清单（症状 → 原因 → 修法）

| # | 症状 | 真正原因 | 修法 |
|---|---|---|---|
| 1 | `Bad configuration option: \357\273\277#` | 配置文件有 **BOM**（PowerShell `-Encoding utf8` 会加） | 用 **Python 写 utf-8 无 BOM**；二进制读回后剥掉前 3 字节 |
| 2 | CI 构建出 `*-unsigned.apk` | `.gitignore` 的 `keystore.properties` **匹配了任意层级** → 公开副本没被提交 | 改成 `/keystore.properties`；`git check-ignore -v <path>` 验证 |
| 3 | Linux runner `./gradlew: Permission denied` | Windows 提交的 gradlew **没有 +x** | `git update-index --chmod=+x gradlew` + CI 里 `chmod +x` |
| 4 | CI 在 setup-android 步骤失败 | 第三方 action 与 runner **预装 SDK 冲突** | 删掉 action，直接用预装 `sdkmanager` |
| 5 | 无法创建 Release | 新仓库 `GITHUB_TOKEN` 默认**只读** | workflow 加 `permissions: { contents: write }` |
| 6 | `apksigner` 找不到/不可执行 | 不在 PATH、缺可执行位、`JAVA_HOME` 未设 | 自动定位 `$ANDROID_HOME/build-tools/*`，用 `sh` 执行，CI 里设 `JAVA_HOME` |
| 7 | Release 无附件但步骤"成功" | `upload-artifact` 找不到文件只**警告**；release 步骤也没校验 | `if-no-files-found: error` + `fail_on_unmatched_files: true` + 先 `find` 规范化路径 |
| 8 | tag 重推后 CI 仍跑旧 workflow | 远程 tag 删了，但**本地 tag 还在旧提交** | `git tag -f -a` 重新指向，再 `git push -f origin <tag>` |
| 9 | 下载校验"通过"但其实是半个包 | `--max-time` 截断仍返回 200 | **比对 size 与 digest** |
| 10 | PowerShell 里 `[xml]$x = Get-Content ...` 报"意外标记" | 默认编码读中文测试名 | `Get-Content -Raw -Encoding UTF8` |
| 11 | PowerShell 脚本报 `无法将"if"项识别为...` | 内联 `if` 表达式在 PS5 不支持 | 用 `$(if (...) {..} else {..})` 或先赋值 |

---

## 10. 每次会话的检查清单

- [ ] 跑第 1 节四条诊断命令，**记录结论**（不要猜）
- [ ] 若能 `ssh -T git@github.com` 通过 → 直接推（最省事）
- [ ] 否则配 SSH over 443（第 2 节），让用户加一次公钥
- [ ] 推送前：`git status`、`versionCode/versionName` 已改、`git log` 无遗漏
- [ ] 推送：main 用**重试循环**；成功判据看输出里的 `main -> main`（**不看退出码**）
- [ ] 发版：`git tag -f -a` → `git push origin <tag>` → 等 CI
- [ ] 验收：API 取 size/digest → 下载 → **比对** → `aapt` 看版本 → `apksigner` 看签名
- [ ] 收尾：确认后台任务结束、临时文件清理、把结果与链接写给用户

---

## 11. 给用户的最短说明（可直接转述）

> 首次需要你在浏览器点一次：把公钥贴到 `github.com/settings/ssh/new`。
> 之后 agent 就能在宽带下直接推送、打 tag、让 CI 自动出包；
> 每次发版的安装包会挂在仓库的 Releases 页面，任何人都能下载。
