# X 大屏（X-BigTV）

在 Android 电视盒子上刷 X（x.com）：**遥控器上下看帖 + 抖音式竖屏视频流 + 手机扫码/粘贴 Cookie 登录 + 独立代理**。

- 包名 `io.github.xbigtv.tv` ・ 目标 ABI `armeabi-v7a`（纯 Java，无 `.so`）・ minSdk 21
- 下载：见 [Releases](../../releases/latest)（`X-BigTV-*-armv7a.apk`）

## 功能

- **登录**：电视起一个局域网小服务，手机扫码后可镜像电视画面、点按操作、直接粘贴 `auth_token` 登录；
  电视端也能用遥控器软键盘粘贴 Cookie。
- **看帖**：顶部 tab 与网页一致——为你推荐 / 关注 / 视频 + **你 Pin 到首页的分组（列表）**；
  多图九宫格、X 原生 Grok 翻译、引用帖、转推、浏览量。图片帖先看详情与评论，详情里再点图片全屏翻页。
- **短视频**：视频帖 OK 进入沉浸式竖屏流，上下切换、播完自动下一条；
  **从某个分组进来就继续翻该分组**，边翻页边筛视频帖；播放 10 秒后自动隐藏作者信息，任意按键唤回。
- **播放器**：HLS 自适应、清晰度、倍速（面板内 ▲▼ 0.1 步进，跨视频记忆）、
  快进快退 10s、续播、竖屏居中不拉伸 + 「旋转 90°」铺满、点赞。
- **代理**：只作用于本 App（接口/图片/视频/登录页统一走代理），支持 HTTP(S) 与 SOCKS5、账号密码，
  并且可以**手机扫码填写**（电视出二维码，手机提交即生效）。
- **自动更新**：读 GitHub Release，启动时静默检查（每天一次），设置里可手动检查并一键升级。

## 遥控器按键

| 场景 | 按键 | 行为 |
| --- | --- | --- |
| 看帖列表 | ▲▼ | 移动焦点、翻页 |
| | OK | 视频→竖屏流；图片→全屏查看；纯文字→帖子详情 |
| | 长按 OK | 刷新并回到最上面 |
| | 菜单 | 设置 |\n| | 返回 | 连按两次退出 |
| 竖屏视频 | ▲▼ | 上一条 / 下一条 |
| | OK | 播放 / 暂停 |
| | ◀▶ | 快退 / 快进 10 秒 |
| | 菜单 | 控制面板（倍速 ▲▼ 调 0.1、清晰度、画面、旋转、连播、声音、点赞、原帖） |
| 全屏看图 | ◀▶ / OK / 返回 | 翻页 / 放大还原 / 退出 |

## 安装与升级

1. 下载 `X-BigTV-*-armv7a.apk` 直接安装（覆盖安装保留登录态与设置）。
2. 首次进入 App → 右上「设置代理」填好代理（服务器填**跑代理的那台电脑/路由的局域网 IP**，不是 `127.0.0.1`）。
3. 再用手机扫左下角二维码：页面里粘贴 `auth_token` 即可登录，电视自动进入主界面。
4. 之后 App 会自己检查 GitHub 上的新版本，弹窗确认后下载并拉起安装器。

## 构建

```powershell
$env:JAVA_HOME='<JDK17>'
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # 解析器单测
```

签名配置放在 `keystore.properties`（已被 `.gitignore` 忽略，不入库）。
打 `v*` tag 会触发 GitHub Actions 自动构建 + 发布签名 APK（见 `.github/workflows/release.yml`）。

## 文档

- 实现细节（分层、代理、登录桥、播放器、发布流程）：[docs/TECH.md](docs/TECH.md)
- x.com 接口调研（GraphQL queryId、请求头、返回结构、视频变体）：[docs/API-RESEARCH.md](docs/API-RESEARCH.md)
- 变更记录：[CHANGELOG.md](CHANGELOG.md)

## 说明

- 仅供个人在大屏上浏览使用；接口是 x.com Web 端私有接口，改版后可能需要刷新 queryId（设置里有按钮）。
- 盒子自带的旧 WebView（如 Chromium 83）渲染不了 x.com 现在的登录页，App 会提示改用 Cookie 登录。
