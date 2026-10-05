# 签名密钥（公开）

本目录**故意公开**本项目的 Android 签名密钥，目的是让**任何人都能构建出可以覆盖安装的升级包**。

| 文件 | 内容 |
|---|---|
| `tavern.jks` | 签名密钥（别名 `tavern`）|
| `keystore.properties` | 密码：`tavern2026`（store 与 key 同密码）|

## ⚠️ 风险提示（请务必读完）

1. **任何人都能用这个密钥签名出"同包名"的 APK**，并且能**直接覆盖安装**在已装本应用的设备上。
2. 这意味着：**如果有人在别处分发一个用这个密钥签名的恶意 APK，系统会把它当成官方升级**。
3. 因此请只从**你信任的来源**获取安装包，并尽量核对构建来源（源码 tag / CI 产物哈希）。
4. 本项目采用 GPL-3.0：衍生作品**必须同样开源**。如果你要发布自己的修改版，**建议改用你自己的 `applicationId`**（例如 `com.tavern.chat.fork`）与自己的密钥 —— 这样可以与本应用**并存安装**，互不覆盖。

## 怎么用它构建

```bash
git clone <repo>
cd fletapp-native
./gradlew :app:assembleRelease      # 会自动读取 signing/keystore.properties 完成签名
```

产物：`app/build/outputs/apk/release/app-release.apk`
