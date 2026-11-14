# 技术说明（TECH）

面向维护者。使用说明见 [README](../README.md)，接口细节见 [API-RESEARCH](API-RESEARCH.md)。

## 1. 分层

```
ui/            纯 Java 程序化布局（无 layout XML），全部自己处理焦点
xapi/          GraphQL 客户端 + 时间线解析（不依赖 android.*，可 JVM 单测）
net/           OkHttp 单例 + 本地正向代理（LocalProxy）
login/         WebView 登录页 + 局域网扫码桥（LanLinkServer）
update/        GitHub Release 自动更新
data/Prefs     所有本地设置
```

- `xapi` 层刻意不引 `android.text.TextUtils` 之类，改用 `Str`，因此 `TimelineParser`、
  `MediaItem`、`Ops` 都能直接在 JVM 单测里跑（`app/src/test`）。
- UI 全部在代码里搭：电视盒子对 XML 里的 weight/焦点继承很敏感，程序化更容易保证焦点环、
  间距和 4K/1080p 的一致性。

## 2. 网络与代理

```
OkHttp / ExoPlayer ─┐
                    ├─→ 127.0.0.1:<随机端口> (LocalProxy) ─→ 上游 HTTP 代理 或 SOCKS5
WebView ────────────┘
```

要点（都是真机踩出来的）：

1. **WebView 会锁定创建时的代理配置**：必须「先 `setProxyOverride`，回调回来再 `new WebView`」，
   否则页面直连、报 `ERR_EMPTY_RESPONSE`，本地转发端口一条 CONNECT 都收不到。
   代码见 `LoginActivity.applyProxyThenStartWeb()`，代理变化时用 `restartWebWithProxy()` 重建 WebView。
2. **Android 的 `Socket.connect()` 不接受未解析地址**（`createUnresolved` 会抛
   `UnknownHostException`），所以代理服务器本身要本地解析，只把目标域名保持 unresolved 交给代理端解析。
3. **隧道必须双向并发泵送**：单向 `pump` 要等 EOF 才返回，只做一个方向会白屏。
4. 上游 HTTP 代理用预置 `Proxy-Authorization: Basic`；SOCKS5 用 `java.net.Proxy` + `Authenticator`。

## 3. 登录

x.com 没有官方二维码登录接口（web bundle 里没有 `qrCode`/`device_code` 之类），所以：

- 电视用 WebView 打开官方登录页，同时 `LanLinkServer` 在局域网开一个 HTTP 服务；
- 二维码 = `http://<TV-IP>:<port>/x/<key>`，手机扫码后：
  - `/f/<key>` 取电视画面（`WebView.draw()` → JPEG，约 1fps）；
  - `/t/<key>` 点手机 = 点电视（`MotionEvent` 派发给 WebView），验证码/2FA 都能过；
  - `/y/<key>` 手机输入的文字写进当前聚焦的 input；
  - `/c/<key>` 手机粘贴 `auth_token` → 电视用 `XApi.bootstrap()` 访问一次 `x.com/home`
    从 `Set-Cookie` 里换出 `ct0`/`twid`，再拉一次时间线校验，成功即保存会话。
- 登录态判定不看 URL，而是 `CookieManager` 里的 `auth_token` + `ct0`。

**老盒子限制**：Chromium 83（2020）解析不了 x.com 现在的 JS（`Uncaught SyntaxError: Unexpected
reserved word`），网页登录不可用；登录页会检测 WebView 主版本号 < 100 时给出提示，引导走 Cookie 通道。

## 4. 时间线与解析

- `TimelineParser` 兼容 X 现在并存的两套结构：
  - 用户：新结构 `core.name/core.screen_name + avatar.image_url`，旧结构 `legacy.name/profile_image_url_https`；
  - 转推在 `legacy.retweeted_status_result`，**引用帖在帖子 result 的顶层 `quoted_status_result`**（易写错）；
  - 长文正文在 `note_tweet.note_tweet_results.result.text`；
  - 翻译在 `grok_translated_post_with_availability.data.translation`（和网页一致，无需另调接口）。
- `queryId` 会随 X 改版变化：`Ops` 内置实测值，并在启动后抓 `abs.twimg.com` 的 JS bundle
  （正则 `queryId:"…",operationName:"…"`）刷新覆盖；接口 404 时自动刷新并重试一次。
- 注意 `HomeTimeline`/`HomeLatestTimeline` 的注册在按需加载的 chunk 里，官网 HTML 引用到的
  `main.js`/`vendor.js` 里没有它们，所以**必须内置默认值**。

## 5. 播放器

- Media3/ExoPlayer + `media3-exoplayer-hls` + `media3-datasource-okhttp`（播放也走代理）。
- **必须用 TextureView**：在这个 4K 盒子上 `SurfaceView` 会被父容器背景层盖住，表现为
  「有声音、画面全黑」。因此 `AspectRatioFrameLayout` 里放 `TextureView`，`player.setVideoTextureView()`。
- 竖屏视频用 `RESIZE_MODE_FIT` 居中不拉伸；面板里的「旋转 90°」把容器绕中心转 90° 并放大到铺满。
- 续播只做一次（`restoreResumeOnce`），并在读取后立刻清掉记录，避免 `STATE_READY` 反复触发来回 seek。
- 倍速用 `PlaybackParameters` + `Prefs.speed`，切换视频时重新下发，实现跨视频记忆。

## 6. 发布与自动更新

- `.github/workflows/release.yml`：`v*` tag → 单测 → 解码 keystore（4 个 secret）→
  `assembleRelease`（`-PversionName/-PversionCode` 由 tag 推导）→ `apksigner` 校验 →
  创建 GitHub Release 并上传 APK。
- 版本号规则：`major*10000 + minor*100 + patch`（`0.2.3` → `203`），`UpdateManager.codeOf()` 与
  `build.gradle` 的 `versionCode` 保持一致。
- App 侧 `UpdateManager`：`releases/latest` → 比较版本 → 下载到 `cacheDir` → `FileProvider`
  + `ACTION_VIEW` 拉起安装器；需要 `REQUEST_INSTALL_PACKAGES`。
- 签名密钥必须稳定：换 key 会导致覆盖安装失败，只能卸载重装。

## 7. 调试清单（真机）

```powershell
adb connect <TV-IP>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb exec-out screencap -p > shot.png                 # 看画面
adb logcat -d -s XBigTV:*                            # LocalProxy / 登录页 / 探针日志
adb shell input keyevent 20 / 19 / 23 / 4            # 下 / 上 / OK / 返回
adb shell input keyevent --longpress 23              # 长按 OK（刷新）
```

`NetProbe`（仅 debug 构建）会在登录页启动时打三条探针：HTML、GraphQL（无 cookie 应为 403）、
视频分片（应为 206），用来区分「代理没通」和「Cloudflare/服务端拒绝」。
