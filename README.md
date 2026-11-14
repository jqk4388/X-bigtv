# X 大屏（X-BigTV）

电视端原生 X（x.com）客户端：**遥控器刷推 + 抖音式竖屏视频流 + 手机扫码登录 + 独立代理设置**。

工程对标 `mangguo-tv`（芒果TV 大屏端）的做法：纯 Java、不依赖 Leanback/Compose、全程序化布局、
ExoPlayer(Media3) 播放内核，打包目标 ABI `armeabi-v7a`（minSdk 21），可直接装 32 位 ARM 电视盒子。

- 包名：`io.github.xbigtv.tv`
- 版本：`0.1.1` (versionCode 2)
- APK：`dist/X-BigTV-0.1.1-armv7a-release.apk`（已用 `xbigtv-release.jks` 签名）

---

## 1. 已实现的功能

### 1.1 手机扫码登录
- 进入 App 自动拉起登录页（`login.LoginActivity`）：电视内 WebView 直接加载 **x.com 官方登录页**。
- 电视同时启动一个局域网小服务（`login.LanLinkServer`，纯 Java ServerSocket，无第三方依赖），
  屏幕左下角显示二维码，手机扫码后打开一个手机端页面：
  - **电视画面实时镜像**：手机页面轮询 `/f/<key>` 拿 WebView 的 JPEG 帧（约 1fps，720px 宽）。
  - **点手机画面 = 点电视**：`/t/<key>` 把归一化坐标转成 `MotionEvent` 派发给 WebView，
    所以图形验证码、二次验证（2FA）、"选择账号"这类交互都能在手机上完成。
  - **手机键盘直填**：点过电视里的输入框后，在手机页面输入框打字 → `/y/<key>` 用注入脚本
    写进当前聚焦的 `<input>`（走原生 setter + `input/change` 事件，React 受控组件也能收到）。
  - 回车/下一步、退格、刷新页面都有对应按钮。
- 登录成功的判定不看 URL，而是轮询 `CookieManager.getCookie("https://x.com")` 里的
  `auth_token` + `ct0`（WebView 的 Cookie 存储能拿到 HttpOnly Cookie），拿到即写入本地会话，
  再调一次 Viewer 接口补上昵称/头像，然后进入主界面。
- 局域网不可达时的兜底：登录页右上角有「遥控器输入」，用自带的 `TvKeyboardDialog` 在电视上直接打字登录。

'### 1.1.1 首次安装就能配代理（重要）
App 一进来就是登录页，登录页刷不出来时如果没有代理入口就等于死锁，所以代理入口铺了三处：
- 登录页右上角常驻蓝色按钮 **「设置代理」**（不看网页能不能加载，永远在）。
- 二维码卡片里一行状态「当前代理：未开启（直连）/ 192.168.1.10:7890 · HTTP」，下面就是
  **「网络打不开？点这里设置代理」**。
- 网页加载失败时中间弹出兜底面板：错误原因 + **「去设置代理」**（默认焦点就落在这个按钮上）+「重新加载」。
- 从代理设置返回登录页会自动重新绑定 WebView 代理并重载页面（`onResume` 比对代理配置变化），
  不用手动退出重进。
- 主界面加载失败时，状态文字本身也可聚焦，按 OK 直接进代理设置。

### 1.2 主界面（普通看帖）'
- 三个时间线：**为你推荐**（`HomeTimeline`）、**关注**（`HomeLatestTimeline`）、**视频**（搜索 `filter:native_video`，Media 产物）。
- 卡片渲染头像/昵称/蓝V/`@handle · 相对时间`/正文（长文自动用 `note_tweet` 全文）/视频或图片缩略图
  （带播放角标和时长）/引用帖/回复·转推·点赞·浏览数；转推会显示"XXX 转推"并展开被转推的原文。
- 遥控器：上下移动焦点（到列表尾部自动翻页，用 `Bottom` cursor），OK 打开，返回退出；菜单键进设置。
- 视频帖 OK → 进入竖屏视频流；纯图片/纯文字帖 OK → 帖子详情（正文 + 回复列表，`TweetDetail`）。

### 1.3 竖屏视频流（抖音式）
- `ui.VideoFeedActivity`：整屏播放器 + 叠加信息层，**上/下键切换视频**，切换时用目标视频缩略图做
  滑动过渡（不用 SurfaceView 动画，避免黑屏/撕裂）。
- 播完自动下一条（可关）；刷到队尾会自动续接一页全局视频（`filter:native_video`）做到无限下滑；
  进入时若有上次进度且视频 > 1 分钟会续播。
- 遥控器映射：

| 按键 | 行为 |
| --- | --- |
| ▲ / ▼ / 频道± / 翻页± | 上一条 / 下一条（沉浸刷） |
| OK / 回车 / 播放暂停键 | 播放 / 暂停 |
| ◀ / ▶ | 快退 / 快进 10 秒 |
| 菜单 | 呼出控制面板 |
| 返回 | 退出视频流 |

- 控制面板（对齐芒果TV播放器的常用项）：**倍速** 0.5/0.75/1/1.25/1.5/2、**清晰度** 自动/1080P/720P/480P/360P、
  **画面** 铺满(ZOOM)/适应(FIT)、**连播** 开/关、**静音**、**点赞**（`FavoriteTweet` 接口）、**查看原帖**、关闭。
- 播放内核：Media3 ExoPlayer 1.4.1 + `media3-exoplayer-hls` + `media3-datasource-okhttp`；
  X 的 `video_info.variants` 里优先取 `application/x-mpegURL`（`/pl/xxx.m3u8`）走 HLS 自适应，
  没有 HLS 时按清晰度档位挑 mp4 码率。底部有细进度条 + 时间码，HUD 4 秒自动隐藏。

### 1.4 独立的代理设置界面
`ui.ProxyActivity`（主界面顶部「代理」按钮 / 设置里的「代理设置（仅本 App）」）：
- 开关、类型（**HTTP(S)** / **SOCKS5**）、服务器、端口、用户名、密码，全部用遥控器软键盘输入。
- 「保存并测试连通性」会真的通过当前代理请求 `x.com/i/api/1.1/account/settings.json` 并回显状态码。
- 生效范围：GraphQL 接口、图片、视频、登录 WebView **全部**走这里；系统代理和别的 App 不受影响。

实现方式（`net.LocalProxy` + `net.Http`）：
1. 代理开启时，App 在 `127.0.0.1` 随机端口起一个正向代理（HTTP `CONNECT` 隧道）。
2. OkHttp 与 ExoPlayer 的 `OkHttpDataSource` 都指向 `127.0.0.1:<port>`。
3. WebView 用 `androidx.webkit` 的 `ProxyController.setProxyOverride()` 也指向同一个本地端口。
4. 上游可以是 HTTP 代理（预置 `Proxy-Authorization: Basic`）或 SOCKS5（`java.net.Proxy` +
   `Authenticator`），**域名用 `createUnresolved` 交给代理端解析**，顺手规避本机 DNS 污染。

> 这样做的原因：WebView 的系统代理接口只认 HTTP 代理、不认 SOCKS5，而且改系统代理会影响整台电视。
> 把上游收口到本地转发端口后，"一个代理设置喂饱所有出网通道"才成立。

### 1.5 其它
- `Ops`：GraphQL `queryId` 内置一份实测值，启动后异步抓 `x.com/home` 的 JS bundle
  （正则 `queryId:"…",operationName:"…"`）覆盖并缓存 12 小时；接口返回 404 时会自动刷新并重试一次。
- `SettingsActivity`：默认清晰度、自动连播、画面比例、账号信息、重新扫码登录、刷新接口版本、版本说明。
- `SearchActivity`：遥控器输入关键词，热门 / 最新 / 视频三种结果，视频结果直接进竖屏流。

---

## 2. 构建

本机（Windows）已验证可直接构建：

```powershell
$env:JAVA_HOME='M:\software\android-build\jdk\jdk-17.0.20.1+1'
./gradlew :app:assembleRelease      # 产物 app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # 解析器单测（4 个用例，含真实抓包 fixture）
```

- SDK：`M:\software\android-build\sdk`（写在 `local.properties`），compileSdk 34 / build-tools 34.0.0。
- 签名：`keystore.properties` → `M:/software/android-build/xbigtv-release.jks`
  （storePassword/keyPassword 见该文件，仅本机使用）。
- ABI：`ndk { abiFilters "armeabi-v7a" }`。App 本体是纯 Java（播放器也不带 `.so`），
  所以 APK 里没有 `lib/` 目录 —— 这一步只是把包声明成 32 位 ARM 目标，任何 ABI 的盒子都能装。

依赖：`androidx.core`、`recyclerview`、`webkit`、`media3-exoplayer(-hls/-ui/-datasource-okhttp)`、
`okhttp 4.12`、`zxing:core 3.5.3`（只用来生成二维码）。全部为纯 Java，无原生库。

---

## 3. 代码结构

```
app/src/main/java/io/github/xbigtv/tv/
├── App.java                  Application：Prefs/Http/XSession 初始化
├── data/Prefs.java           所有本地设置（会话/代理/播放偏好/接口版本缓存）
├── net/
│   ├── Http.java             OkHttpClient 单例（代理变化自动重建）
│   └── LocalProxy.java       本地 127.0.0.1 正向代理（HTTP CONNECT，支持 HTTP/SOCKS5 上游）
├── xapi/
│   ├── XApi.java             GraphQL 客户端：HomeTimeline/HomeLatest/Search/Detail/Favorite/Viewer
│   ├── Ops.java              queryId 表 + 官网 JS 抓取刷新
│   ├── XSession.java         进程内登录态（与 Prefs 同步）
│   ├── TimelineParser.java   GraphQL 时间线解析（新旧两套 user/legacy 结构都兼容）
│   ├── Post.java / MediaItem.java / TimelinePage.java / Str.java
├── login/
│   ├── LoginActivity.java    WebView 登录 + 二维码卡片 + 画面镜像/触控注入
│   ├── LanLinkServer.java    手机扫码用的局域网桥（HTTP 服务 + 手机页面）
│   └── QrBitmap.java         ZXing 生成二维码
└── ui/
    ├── MainActivity.java     三时间线 + 焦点翻页
    ├── PostCardView.java     帖子卡片（手绘布局）
    ├── PostAdapter.java
    ├── VideoFeedActivity.java 竖屏沉浸视频流 + 控制面板
    ├── DetailActivity.java   帖子详情 + 回复
    ├── SearchActivity.java   搜索
    ├── SettingsActivity.java 设置总入口
    ├── ProxyActivity.java    独立代理界面
    ├── ImageLoader.java      LRU + 线程池图片加载
    ├── TvKeyboardDialog.java 遥控器软键盘
    ├── Icons.java / Ui.java  手绘图标与布局工具
```

样式：纯黑背景 `#000`、卡片 `#16181C`、焦点蓝边 `#1D9BF0`，全部程序化绘制，无 layout XML（只有
`values/*.xml` 的颜色/主题和 `res/drawable-nodpi/tv_banner.png` 横幅）。

---

## 4. 已知边界

- **接口是 Web 端私有的**，`queryId` 会随 X 改版变化；已内置自动刷新（用 `Ops`），
  真出现全挂时可在设置里手动「刷新接口版本」。
- 登录依赖 X 官方登录页，不破解验证码；遇到拦人机校验时，用手机镜像页在手机上点一下即可。
- 扫码要求手机和电视在**同一局域网**（频道/盒子常见的多网段情况请用 2.4G/5G 同段）。
- 竖屏流的内容源是"时间线里的视频 + `filter:native_video` 搜索结果"，不是 X 的推荐算法本身。
- 本工程只做个人大屏浏览，未实现发帖/私信/通知。

更多接口细节：见 [docs/API-RESEARCH.md](docs/API-RESEARCH.md)。
