# x.com 接口调研记录（2026-11-14，Edge 实机抓包）

抓包方式：在已登录的 x.com 页面上挂钩 `window.fetch` / `XMLHttpRequest.prototype.open`，
然后滚动时间线触发真实请求，从 `performance`/钩子里读取 URL、请求头和 body。

## 1. 请求头（登录态）

```
POST https://x.com/i/api/graphql/<queryId>/<operationName>
authorization: Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA
content-type: application/json
cookie: auth_token=…; ct0=…; twid=…; lang=zh-cn
x-csrf-token: <ct0 的值>
x-twitter-active-user: yes
x-twitter-auth-type: OAuth2Session
x-twitter-client-language: zh-cn
origin: https://x.com
referer: https://x.com/home
```

**关键结论：不需要 `x-client-transaction-id`。**
网页端会带一个每请求变化的 `x-client-transaction-id`（由前端 bundle 计算），但实测把它去掉，
只保留上面的 Bearer + Cookie + `x-csrf-token`，`HomeTimeline` 依然返回 `200` 和完整时间线
（261 KB）。所以客户端不需要复刻 transaction-id 生成算法。

登录态失效表现为 `401`/`403`；限流为 `429`；`queryId` 过期表现为 `404`（或 `errors` 里的
"Query not found"）。

## 2. queryId（2026-11-14 实测）

| operationName | queryId | 用途 |
| --- | --- | --- |
| `HomeTimeline` | `KmJCeZ__ef1fNos7tKN_fg` | 为你推荐 |
| `HomeLatestTimeline` | `IVA5BUbeYPTrpYekQH7jiA` | 关注（最新） |
| `SearchTimeline` | `vLHjqbDv7TPFMBkIIO1nOA` | 搜索 / 视频流 |
| `TweetDetail` | `dQDdKtt882nl-Km98FJGSQ` | 帖子详情 + 回复 |
| `UserTweets` | `D6z-FJI2pKuwEeYYxVIGbg` | 用户时间线 |
| `UserMedia` | `iTSlWbsmD00dAE7Sk9xvIw` | 用户媒体 |
| `UserByScreenName` | `AMIBMjtxEEATh4z8V9GtRg` | 用户名 → 用户 |
| `TweetResultByRestId` | `U8lc5aomF5krJzZ9vJ9wxg` | 单帖 |
| `TweetResultsByRestIds` | `yW_CmYWEKwaOpAEHpNSReg` | 多帖 |
| `FavoriteTweet` | `lI07N6Otwv1PhnEgXILM7A` | 点赞（mutation） |
| `Viewer` | `TpQxQtWMTMnIAMPlMGMqNw` | 当前账号信息 |
| `Likes` | `ZjyvI1V9gajq5eclHCObzA` | 点赞列表 |
| `ExplorePage` | `CVExg1KY6Ec9BioRBbI8Jg` | 探索页 |

这些 id 也存在于官网 bundle 里，格式固定：

```js
e.exports={queryId:"KmJCeZ__ef1fNos7tKN_fg",operationName:"HomeTimeline",operationType:"query",metadata:{featureSwitches:[],fieldToggles:[]}}
```

因此 App 的 `Ops` 会在启动时抓 `https://x.com/home` 的
`https://abs.twimg.com/responsive-web/client-web/*.js`，用
`queryId:"([A-Za-z0-9_\-]+)",operationName:"([A-Za-z0-9_]+)"` 批量刷新并落到本地缓存。
注意：`HomeTimeline`/`HomeLatestTimeline` 的注册代码在按需加载的 chunk 里，HTML 里引用的
`main.js` + `vendor.js` 里没有它们（实测 main+vendor 共 105 个 operation，缺这两个），
所以**必须内置默认值**，不能只靠运行时抓取。

## 3. 请求体

统一为 `{ "variables": {...}, "features": {...} }`，`features` 是 40+ 个开关的扁平对象。
App 内置一份实测可用的并集（`XApi.FEATURES`，52 个键），多传不报错。

时间线：

```json
{"variables":{"count":20,"includePromotedContent":false,"withCommunity":true,
"requestContext":"launch","latestControlAvailable":true,"enableRanking":false,
"seenTweetIds":[],"cursor":"<Bottom cursor>"},"features":{...}}
```

搜索（Media 产物就是视频结果）：

```json
{"variables":{"rawQuery":"filter:native_video","count":20,"querySource":"typed_query","product":"Media"},"features":{...}}
```

`product` 取值：`Top` / `Latest` / `Media` / `People` / `Photos`。
`rawQuery` 支持运算符，`filter:native_video` 实测一页能返回 40+ 条原生视频（含转推），
`product:"Media"` + `filter:native_video` 是最适合做竖屏视频流的组合。

帖子详情：

```json
{"variables":{"focalTweetId":"<id>","with_rux_injections":false,"rankingMode":"Relevance",
"includePromotedContent":false,"withCommunity":true,"withQuickPromoteEligibilityTweetFields":true,
"withBirdwatchNotes":true,"withVoice":true},"features":{...}}
```

## 4. 响应结构（要点）

时间线根路径随 operation 不同：

| operation | 路径 |
| --- | --- |
| HomeTimeline / HomeLatestTimeline | `data.home.home_timeline_urt.instructions` |
| SearchTimeline | `data.search_by_raw_query.search_timeline.timeline.instructions` |
| TweetDetail | `data.threaded_conversation_with_injections_v2.instructions` |
| UserTweets | `data.user.result.timeline_v2.timeline.instructions` |

`instructions[].entries[]`：

- `content.__typename == "TimelineTimelineItem"` → `content.itemContent.tweet_results.result`
- `content.__typename == "TimelineTimelineModule"` → `content.items[].item.itemContent.tweet_results.result`
- `content.__typename == "TimelineTimelineCursor"` → `cursorType` = `Top`/`Bottom`，`value` 是翻页游标
- `entryId` 以 `promoted` 开头的是广告，直接丢；`result.__typename` 可能是
  `Tweet` / `TweetWithVisibilityResults`（真正的贴在 `.tweet` 里）/ `TweetTombstone`（不可见，丢）

**用户对象是 2025 之后的新结构**（`user_results.result.legacy` 已经没了）：

```json
{"__typename":"User","rest_id":"…","is_blue_verified":false,
 "avatar":{"image_url":"https://pbs.twimg.com/profile_images/…_normal.jpg"},
 "core":{"name":"…","screen_name":"…","created_at":"…"},
 "verification":{"verified":true},
 "relationship_counts":{"followers":1,"following":2}}
```

头像要手动把 `_normal` 换成 `_200x200`。解析器同时保留旧 `legacy.name/profile_image_url_https`
的兜底分支。

帖子本体（`legacy`）关键字段：`id_str` `full_text` `created_at`
（`EEE MMM dd HH:mm:ss Z yyyy`）`favorite_count` `retweet_count` `reply_count` `quote_count`
`favorited` `possibly_sensitive` `is_quote_status` `extended_entities.media[]`
`retweeted_status_result.result`（转推）；浏览量在 `views.count`（可能是
`{"state":"Enabled"}` 没有 count）。

- 长文：`note_tweet.note_tweet_results.result.text` 才是全文，`legacy.full_text` 只是截断版。
- 引用帖：`quoted_status_result` 在**帖子 result 的顶层**（不是 `legacy` 里），
  而转推的 `retweeted_status_result` 在 **`legacy` 里** —— 这一对不同层级很容易写错。
- 正文里的 `&amp;` `&lt;` `&gt;` `&quot;` `&#39;` 需要反转义。

视频媒体：

```json
{"type":"video","media_url_https":"https://pbs.twimg.com/amplify_video_thumb/…jpg",
 "original_info":{"width":1280,"height":720},
 "additional_media_info":{"monetizable":false},
 "video_info":{"aspect_ratio":[16,9],"duration_millis":8033,
   "variants":[
     {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/amplify_video/<id>/pl/<x>.m3u8?tag=29"},
     {"bitrate":256000,"content_type":"video/mp4","url":"https://video.twimg.com/amplify_video/<id>/vid/avc1/480x270/<x>.mp4?tag=29"},
     {"bitrate":832000,"content_type":"video/mp4","url":"…/640x360/….mp4?tag=29"},
     {"bitrate":2176000,"content_type":"video/mp4","url":"…/1280x720/….mp4?tag=29"}
   ]}}
```

- `variants[]` 第一项基本固定是 HLS 主播放列表（fMP4 + MPEG-TS 混合，ExoPlayer 直接能吃）。
- ms 级 `duration_millis`；`type` 为 `animated_gif` 时只有 mp4 变体、没有时长，需要循环播放。
- 播放时建议带 `referer: https://x.com/`（App 已加），并对 URL 里的 `?tag=` 原样保留。

## 5. 登录（没有官方 QR 接口，本工程的方案）

x.com 的 Web 端**没有**扫码登录：`main.js` 里搜 `qrCode/qr_code/scanQr/device_code` 全部 0 命中，
登录只有 `/i/flow/login`（账号密码 / Google / Apple）。X 的自家 TV App 走的是私有客户端流程，
没有公开接口可用。

因此本工程用"**电视内 WebView 登录 + 手机扫码接管**"：

1. 电视加载官方登录页（桌面 UA），同时在本机 LAN 起 HTTP 桥（`LanLinkServer`，随机端口 + 8 位随机 key）。
2. 二维码内容 = `http://<TV 局域网 IP>:<port>/x/<key>`。
3. 手机页面：`/f/<key>` 轮询电视画面（`WebView.draw()` 到 Bitmap → JPEG，因硬件层 draw 抓不到内容，
   登录页 WebView 特意设为 `LAYER_TYPE_SOFTWARE`）；`/t/<key>` 把点击坐标换算成 `MotionEvent`
   派发给 WebView；`/y/<key>` 把手机输入的文字写进当前聚焦的 input。
4. 判定成功：轮询 `CookieManager` 的 `auth_token` + `ct0`（Cookie 存储可读 HttpOnly），
   写会话 → `Viewer` 补昵称/头像 → 进主界面。

这条链路天然能过图形验证码和 2FA，因为用户看的就是真实的官方页面。

## 6. 其它实测结论

- 地区/网络：本机 Edge 能直连 x.com，但 JVM/命令行直连超时（该机器依赖浏览器侧代理），
  所以 App 的代理功能是必需的，且必须覆盖 WebView 与播放器（见 README 1.4）。
- `video.twimg.com` 的 m3u8 与分片不需要 Cookie，只需要正常 UA。
- 图片域名 `pbs.twimg.com` 支持 `_200x200` 直接取大图，不用改 `name=` 参数。
- `pbs.twimg.com/profile_images/..._normal.jpg` → `_200x200.jpg` 是最省事的头像放大方式。
