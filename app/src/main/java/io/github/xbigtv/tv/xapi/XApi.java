package io.github.xbigtv.tv.xapi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Locale;

import io.github.xbigtv.tv.BuildConfig;
import io.github.xbigtv.tv.data.Prefs;
import io.github.xbigtv.tv.net.Http;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * x.com 的 Web GraphQL 客户端。实测：只要带上
 * Bearer + auth_token/ct0 Cookie + x-csrf-token，不带 x-client-transaction-id 也能正常返回。
 */
public final class XApi {

    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** X Web 端当前使用的 features 开关集合（实测 2026-11 可用，多传无害）。 */
    public static final String FEATURES = "{"
            + "\"rweb_video_screen_enabled\":false,"
            + "\"rweb_cashtags_enabled\":true,"
            + "\"profile_label_improvements_pcf_label_in_post_enabled\":true,"
            + "\"responsive_web_profile_redirect_enabled\":true,"
            + "\"rweb_tipjar_consumption_enabled\":false,"
            + "\"verified_phone_label_enabled\":false,"
            + "\"creator_subscriptions_tweet_preview_api_enabled\":true,"
            + "\"responsive_web_graphql_timeline_navigation_enabled\":true,"
            + "\"premium_content_api_read_enabled\":false,"
            + "\"communities_web_enable_tweet_community_results_fetch\":true,"
            + "\"c9s_tweet_anatomy_moderator_badge_enabled\":true,"
            + "\"responsive_web_grok_analyze_button_fetch_trends_enabled\":false,"
            + "\"responsive_web_grok_analyze_post_followups_enabled\":true,"
            + "\"rweb_cashtags_composer_attachment_enabled\":true,"
            + "\"responsive_web_jetfuel_frame\":true,"
            + "\"rweb_sports_post_context_enabled\":true,"
            + "\"x_elections_post_context_enabled\":true,"
            + "\"responsive_web_grok_share_attachment_enabled\":true,"
            + "\"responsive_web_grok_annotations_enabled\":true,"
            + "\"articles_preview_enabled\":true,"
            + "\"responsive_web_edit_tweet_api_enabled\":true,"
            + "\"rweb_conversational_replies_downvote_enabled\":false,"
            + "\"graphql_is_translatable_rweb_tweet_is_translatable_enabled\":true,"
            + "\"view_counts_everywhere_api_enabled\":true,"
            + "\"longform_notetweets_consumption_enabled\":true,"
            + "\"responsive_web_twitter_article_tweet_consumption_enabled\":true,"
            + "\"content_disclosure_indicator_enabled\":true,"
            + "\"content_disclosure_ai_generated_indicator_enabled\":true,"
            + "\"responsive_web_grok_show_grok_translated_post\":true,"
            + "\"responsive_web_grok_analysis_button_from_backend\":true,"
            + "\"post_ctas_fetch_enabled\":false,"
            + "\"freedom_of_speech_not_reach_fetch_enabled\":true,"
            + "\"standardized_nudges_misinfo\":true,"
            + "\"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled\":true,"
            + "\"longform_notetweets_rich_text_read_enabled\":true,"
            + "\"longform_notetweets_inline_media_enabled\":false,"
            + "\"responsive_web_nested_quote_preview_enabled\":true,"
            + "\"responsive_web_grok_image_annotation_enabled\":true,"
            + "\"responsive_web_grok_imagine_annotation_enabled\":true,"
            + "\"responsive_web_made_on_x_label_enabled\":false,"
            + "\"responsive_web_grok_community_note_auto_translation_is_enabled\":true,"
            + "\"responsive_web_enhance_cards_enabled\":false,"
            + "\"rweb_video_timestamps_enabled\":true,"
            + "\"tweet_awards_web_tipping_enabled\":false,"
            + "\"responsive_web_graphql_exclude_directive_enabled\":true,"
            + "\"responsive_web_graphql_skip_user_profile_image_extensions_enabled\":false,"
            + "\"creator_subscriptions_quote_tweet_preview_enabled\":false,"
            + "\"hidden_profile_subscriptions_enabled\":true,"
            + "\"subscriptions_verification_info_is_identity_verified_enabled\":true,"
            + "\"subscriptions_verification_info_verified_since_enabled\":true,"
            + "\"highlights_tweets_tab_ui_enabled\":true,"
            + "\"responsive_web_twitter_article_notes_tab_enabled\":true,"
            + "\"subscriptions_feature_can_gift_premium\":false"
            + "}";

    private XApi() {
    }

    /* ---------------- 基础请求 ---------------- */

    private static Request.Builder base(String url) {
        Request.Builder b = new Request.Builder()
                .url(url)
                .header("authorization", "Bearer " + BuildConfig.X_BEARER)
                .header("content-type", "application/json")
                .header("user-agent", UA)
                .header("accept", "*/*")
                .header("accept-language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("origin", "https://x.com")
                .header("referer", "https://x.com/home")
                .header("x-twitter-active-user", "yes")
                .header("x-twitter-client-language", "zh-cn");

        String auth = XSession.authToken;
        String ct0 = XSession.ct0;
        String twid = XSession.twid;
        if (!Str.isEmpty(auth)) {
            b.header("x-twitter-auth-type", "OAuth2Session");
            StringBuilder cookie = new StringBuilder();
            cookie.append("auth_token=").append(auth);
            if (!Str.isEmpty(ct0)) {
                cookie.append("; ct0=").append(ct0);
                b.header("x-csrf-token", ct0);
            }
            if (!Str.isEmpty(twid)) {
                cookie.append("; twid=").append(twid);
            }
            cookie.append("; lang=zh-cn");
            b.header("cookie", cookie.toString());
        }
        return b;
    }

    private static String post(String operation, String variablesJson) throws IOException {
        String qid = Ops.id(operation);
        if (Str.isEmpty(qid)) {
            throw new IOException("缺少接口 ID: " + operation);
        }
        String url = "https://x.com/i/api/graphql/" + qid + "/" + operation;
        String body = "{\"variables\":" + variablesJson + ",\"features\":" + FEATURES + "}";
        OkHttpClient client = Http.client();
        Request req = base(url).post(RequestBody.create(body, JSON)).build();
        try (Response resp = client.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (resp.code() == 401 || resp.code() == 403) {
                throw new IOException("登录态失效（" + resp.code() + "），请重新扫码登录");
            }
            if (resp.code() == 429) {
                throw new IOException("请求过于频繁（429），稍后再试");
            }
            if (resp.code() == 404) {
                throw new IOException("404 接口版本可能已过期，正在尝试自动刷新");
            }
            if (!resp.isSuccessful() && text.isEmpty()) {
                throw new IOException("接口错误 HTTP " + resp.code());
            }
            return text;
        }
    }

    /* ---------------- 时间线 ---------------- */

    public static TimelinePage home(boolean following, String cursor) throws IOException {
        JSONObject v = new JSONObject();
        try {
            v.put("count", 20);
            if (!Str.isEmpty(cursor)) {
                v.put("cursor", cursor);
            }
            v.put("includePromotedContent", false);
            v.put("withCommunity", true);
            v.put("requestContext", "launch");
            v.put("latestControlAvailable", true);
            if (following) {
                v.put("enableRanking", false);
                v.put("seenTweetIds", new JSONArray());
            }
        } catch (Exception e) {
            throw new IOException("构造请求失败");
        }
        String op = following ? "HomeLatestTimeline" : "HomeTimeline";
        return TimelineParser.parseTimeline(post(op, v.toString()));
    }

    /**
     * 视频流来源：X 搜索的 Media 产物 + filter:native_video，实测一页能出 40+ 条原生视频。
     */
    public static TimelinePage videoFeed(String keyword, String cursor) throws IOException {
        String q = Str.isEmpty(keyword) ? "filter:native_video"
                : (keyword.trim() + " filter:native_video");
        return search(q, "Media", cursor);
    }

    public static TimelinePage search(String rawQuery, String product, String cursor) throws IOException {
        JSONObject v = new JSONObject();
        try {
            v.put("rawQuery", rawQuery);
            v.put("count", 20);
            v.put("querySource", "typed_query");
            v.put("product", Str.isEmpty(product) ? "Top" : product);
            if (!Str.isEmpty(cursor)) {
                v.put("cursor", cursor);
            }
        } catch (Exception e) {
            throw new IOException("构造请求失败");
        }
        return TimelineParser.parseTimeline(post("SearchTimeline", v.toString()));
    }

    public static TimelinePage detail(String tweetId) throws IOException {
        JSONObject v = new JSONObject();
        try {
            v.put("focalTweetId", tweetId);
            v.put("with_rux_injections", false);
            v.put("rankingMode", "Relevance");
            v.put("includePromotedContent", false);
            v.put("withCommunity", true);
            v.put("withQuickPromoteEligibilityTweetFields", true);
            v.put("withBirdwatchNotes", true);
            v.put("withVoice", true);
        } catch (Exception e) {
            throw new IOException("构造请求失败");
        }
        return TimelineParser.parseTimeline(post("TweetDetail", v.toString()));
    }

    public static TimelinePage userTweets(String userId, String cursor) throws IOException {
        JSONObject v = new JSONObject();
        try {
            v.put("userId", userId);
            v.put("count", 20);
            v.put("includePromotedContent", false);
            v.put("withQuickPromoteEligibilityTweetFields", true);
            v.put("withVoice", true);
            if (!Str.isEmpty(cursor)) {
                v.put("cursor", cursor);
            }
        } catch (Exception e) {
            throw new IOException("构造请求失败");
        }
        return TimelineParser.parseTimeline(post("UserTweets", v.toString()));
    }

    /* ---------------- 互动 ---------------- */

    public static boolean like(String tweetId, boolean on) throws IOException {
        JSONObject v = new JSONObject();
        try {
            v.put("tweet_id", tweetId);
        } catch (Exception ignored) {
        }
        String body = post("FavoriteTweet", v.toString());
        try {
            JSONObject o = new JSONObject(body);
            JSONObject data = o.optJSONObject("data");
            if (data != null && data.has("favorite_tweet")) {
                return true;
            }
        } catch (Exception ignored) {
        }
        throw new IOException("操作失败");
    }

    /* ---------------- 账号信息 ---------------- */

    /**
     * 只要手里有 auth_token，就能换到 ct0/twid：
     * 带着 auth_token 访问一次 x.com/home，服务端会在 Set-Cookie 里补上 ct0（CSRF token）和 twid。
     * 这样用户在手机页面上只需要粘一个 auth_token。
     */
    public static String[] bootstrap(String authToken) {
        if (Str.isEmpty(authToken)) {
            return null;
        }
        final java.util.Map<String, String> jar = new java.util.HashMap<>();
        try {
            OkHttpClient plain = Http.client().newBuilder()
                    .cookieJar(new okhttp3.CookieJar() {
                        @Override
                        public void saveFromResponse(okhttp3.HttpUrl url, java.util.List<okhttp3.Cookie> cookies) {
                            for (okhttp3.Cookie ck : cookies) {
                                jar.put(ck.name(), ck.value());
                            }
                        }

                        @Override
                        public java.util.List<okhttp3.Cookie> loadForRequest(okhttp3.HttpUrl url) {
                            java.util.List<okhttp3.Cookie> out = new java.util.ArrayList<>();
                            for (java.util.Map.Entry<String, String> e : jar.entrySet()) {
                                try {
                                    out.add(new okhttp3.Cookie.Builder()
                                            .domain("x.com").path("/")
                                            .name(e.getKey()).value(e.getValue())
                                            .httpOnly().secure().build());
                                } catch (Exception ignored) {
                                }
                            }
                            return out;
                        }
                    })
                    .build();
            Request req = new Request.Builder()
                    .url("https://x.com/home")
                    .header("user-agent", UA)
                    .header("accept", "text/html,application/xhtml+xml")
                    .header("cookie", "auth_token=" + authToken)
                    .build();
            try (Response resp = plain.newCall(req).execute()) {
                if (resp.body() != null) {
                    resp.body().string();
                }
            }
        } catch (Exception e) {
            return null;
        }
        String ct0 = jar.get("ct0");
        String twid = jar.get("twid");
        if (Str.isEmpty(ct0)) {
            return null;
        }
        return new String[]{authToken, ct0, twid == null ? "" : twid};
    }

    /** 会话可用性自检：能拉到时间线才算真的登录成功。 */
    public static String verifySession() {
        try {
            TimelinePage page = home(false, "");
            if (!page.posts.isEmpty()) {
                return null;
            }
            return Str.isEmpty(page.error) ? "时间线为空" : page.error;
        } catch (Throwable t) {
            return t.getMessage() == null ? "网络请求失败" : t.getMessage();
        }
    }

    /** 用 Viewer 接口取当前登录账号；失败返回 null。 */
    public static String[] viewer() {
        try {
            String qid = Ops.id("Viewer");
            if (Str.isEmpty(qid)) {
                return null;
            }
            String url = String.format(Locale.US,
                    "https://x.com/i/api/graphql/%s/Viewer?variables=%%7B%%7D&features=%s",
                    qid, URLEncoder.encode(FEATURES, "UTF-8"));
            Request req = base(url).get().build();
            try (Response resp = Http.client().newCall(req).execute()) {
                if (resp.body() == null) {
                    return null;
                }
                String text = resp.body().string();
                JSONObject root = new JSONObject(text);
                JSONObject data = root.optJSONObject("data");
                JSONObject vw = data == null ? null : data.optJSONObject("viewer");
                JSONObject ur = vw == null ? null : vw.optJSONObject("user_results");
                JSONObject user = ur == null ? null : ur.optJSONObject("result");
                if (user == null) {
                    return null;
                }
                Post p = new Post();
                JSONObject fake = new JSONObject();
                fake.put("core", new JSONObject().put("user_results", new JSONObject().put("result", user)));
                TimelineParser.fillAuthor(fake, p);
                return new String[]{p.handle, p.authorName, p.avatarUrl};
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 判断当前代理/网络能否直连 x.com（用于设置页“测试代理”）。 */
    public static String testConnection() {
        try {
            Request req = base("https://x.com/i/api/1.1/account/settings.json").get().build();
            try (Response resp = Http.client().newCall(req).execute()) {
                return resp.code() + (resp.isSuccessful() ? " 连通正常" : " 已连到服务器");
            }
        } catch (Exception e) {
            return "连接失败: " + e.getMessage();
        }
    }
}
