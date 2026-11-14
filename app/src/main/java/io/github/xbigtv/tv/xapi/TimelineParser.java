package io.github.xbigtv.tv.xapi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GraphQL 时间线解析。X 的返回结构有两套并存（旧 legacy / 新的 core+avatar），
 * 这里统一归一化成 {@link Post}。
 */
public final class TimelineParser {

    private TimelineParser() {
    }

    public static TimelinePage parseTimeline(String body) {
        TimelinePage page = new TimelinePage();
        try {
            JSONObject root = new JSONObject(body);
            if (root.has("errors")) {
                JSONArray errs = root.optJSONArray("errors");
                if (errs != null && errs.length() > 0) {
                    JSONObject e = errs.optJSONObject(0);
                    page.error = e != null ? e.optString("message", "接口返回错误") : "接口返回错误";
                }
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                if (page.error.isEmpty()) {
                    page.error = "接口未返回数据";
                }
                return page;
            }
            JSONArray instructions = findInstructions(data);
            if (instructions == null) {
                page.error = "时间线结构变化";
                return page;
            }
            for (int i = 0; i < instructions.length(); i++) {
                JSONObject ins = instructions.optJSONObject(i);
                if (ins == null) {
                    continue;
                }
                JSONArray entries = ins.optJSONArray("entries");
                if (entries == null && ins.optJSONObject("entry") != null) {
                    entries = new JSONArray();
                    entries.put(ins.optJSONObject("entry"));
                }
                if (entries == null) {
                    continue;
                }
                for (int j = 0; j < entries.length(); j++) {
                    readEntry(entries.optJSONObject(j), page);
                }
            }
        } catch (Exception e) {
            page.error = "解析失败: " + e.getMessage();
        }
        return page;
    }

    private static JSONArray findInstructions(JSONObject data) {
        JSONArray known = dig(data,
                "home", "home_timeline_urt", "instructions");
        if (known != null) {
            return known;
        }
        known = dig(data, "search_by_raw_query", "search_timeline", "timeline", "instructions");
        if (known != null) {
            return known;
        }
        known = dig(data, "threaded_conversation_with_injections_v2", "instructions");
        if (known != null) {
            return known;
        }
        known = dig(data, "user", "result", "timeline_v2", "timeline", "instructions");
        if (known != null) {
            return known;
        }
        known = dig(data, "list", "tweets_timeline", "timeline", "instructions");
        if (known != null) {
            return known;
        }
        return deepFindInstructions(data, 0);
    }

    private static JSONArray deepFindInstructions(Object node, int depth) {
        if (depth > 8 || node == null) {
            return null;
        }
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONArray arr = o.optJSONArray("instructions");
            if (arr != null && arr.length() > 0) {
                return arr;
            }
            for (String k : iterable(o.keys())) {
                JSONArray r = deepFindInstructions(o.opt(k), depth + 1);
                if (r != null) {
                    return r;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && i < 60; i++) {
                JSONArray r = deepFindInstructions(a.opt(i), depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static JSONArray dig(JSONObject o, String... path) {
        Object cur = o;
        for (String p : path) {
            if (!(cur instanceof JSONObject)) {
                return null;
            }
            cur = ((JSONObject) cur).opt(p);
        }
        return cur instanceof JSONArray ? (JSONArray) cur : null;
    }

    private static void readEntry(JSONObject entry, TimelinePage page) {
        if (entry == null) {
            return;
        }
        String entryId = entry.optString("entryId", "");
        if (entryId.startsWith("promoted") || entryId.contains("-promoted-")) {
            return;
        }
        JSONObject content = entry.optJSONObject("content");
        if (content == null) {
            return;
        }
        String type = content.optString("entryType", content.optString("__typename", ""));

        if (content.optString("__typename").equals("TimelineTimelineCursor")
                || "TimelineTimelineCursor".equals(type)) {
            String ct = content.optString("cursorType", "");
            String val = content.optString("value", "");
            if ("Bottom".equalsIgnoreCase(ct)) {
                page.bottomCursor = val;
            }
            return;
        }

        if ("TimelineTimelineModule".equals(content.optString("__typename"))) {
            JSONArray items = content.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    JSONObject ic = it.optJSONObject("item");
                    if (ic == null) {
                        continue;
                    }
                    JSONObject inner = ic.optJSONObject("itemContent");
                    if (inner == null) {
                        continue;
                    }
                    addResult(inner, page);
                }
            }
            return;
        }

        JSONObject itemContent = content.optJSONObject("itemContent");
        if (itemContent == null) {
            return;
        }
        addResult(itemContent, page);
    }

    private static void addResult(JSONObject itemContent, TimelinePage page) {
        String icType = itemContent.optString("__typename", "");
        if (!icType.isEmpty() && !"TimelineTweet".equals(icType)) {
            return;
        }
        JSONObject tweetResults = itemContent.optJSONObject("tweet_results");
        if (tweetResults == null) {
            tweetResults = itemContent.optJSONObject("tweetResult");
        }
        if (tweetResults == null) {
            return;
        }
        JSONObject result = tweetResults.optJSONObject("result");
        Post p = parseTweetNode(result, "");
        if (p != null && !p.id.isEmpty()) {
            page.posts.add(p);
        }
    }

    /* ---------------- 单帖解析 ---------------- */

    public static Post parseTweetNode(JSONObject result, String retweetedBy) {
        if (result == null) {
            return null;
        }
        String typename = result.optString("__typename", "Tweet");
        if ("TweetTombstone".equals(typename) || "TweetUnavailable".equals(typename)) {
            return null;
        }
        if ("TweetWithVisibilityResults".equals(typename)) {
            JSONObject inner = result.optJSONObject("tweet");
            if (inner != null) {
                return parseTweetNode(inner, retweetedBy);
            }
        }
        JSONObject legacy = result.optJSONObject("legacy");
        if (legacy == null) {
            return null;
        }

        Post post = new Post();
        post.id = legacy.optString("id_str", result.optString("rest_id", ""));
        if (post.id.isEmpty()) {
            return null;
        }

        // 转推：把内层帖当作正文
        JSONObject retweeted = null;
        JSONObject rtr = legacy.optJSONObject("retweeted_status_result");
        if (rtr != null) {
            retweeted = rtr.optJSONObject("result");
        }
        if (retweeted != null) {
            Post inner = parseTweetNode(retweeted, "");
            if (inner != null) {
                inner.retweetedBy = nameOf(result);
                return inner;
            }
        }

        fillAuthor(result, post);

        String full = legacy.optString("full_text", "");
        JSONObject note = result.optJSONObject("note_tweet");
        if (note != null) {
            JSONObject nr = note.optJSONObject("note_tweet_results");
            JSONObject nres = nr != null ? nr.optJSONObject("result") : null;
            if (nres != null) {
                String t = nres.optString("text", "");
                if (!t.isEmpty()) {
                    full = t;
                }
            }
        }
        post.text = unescape(full);
        post.likes = legacy.optInt("favorite_count", 0);
        post.retweets = legacy.optInt("retweet_count", 0);
        post.replies = legacy.optInt("reply_count", 0);
        post.quotes = legacy.optInt("quote_count", 0);
        post.sensitive = legacy.optBoolean("possibly_sensitive", false);
        post.liked = legacy.optBoolean("favorited", false);
        post.createdAtMs = parseDate(legacy.optString("created_at", ""));
        JSONObject views = result.optJSONObject("views");
        if (views != null) {
            try {
                post.views = Long.parseLong(views.optString("count", "0"));
            } catch (Exception ignored) {
            }
        }

        // X 网页端自带的 grok 翻译（和网页一致，不需要我们再调接口）
        JSONObject grokTr = result.optJSONObject("grok_translated_post_with_availability");
        if (grokTr != null) {
            JSONObject trData = grokTr.optJSONObject("data");
            if (trData != null) {
                String tr = trData.optString("translation", "");
                if (!tr.isEmpty()) {
                    post.translation = unescape(tr);
                    post.sourceLang = trData.optString("source_language", "");
                }
            }
        }

        JSONObject ent = legacy.optJSONObject("extended_entities");
        if (ent == null) {
            ent = legacy.optJSONObject("entities");
        }
        if (ent != null) {
            JSONArray mediaArr = ent.optJSONArray("media");
            if (mediaArr != null) {
                for (int i = 0; i < mediaArr.length(); i++) {
                    MediaItem m = parseMedia(mediaArr.optJSONObject(i));
                    if (m != null) {
                        post.media.add(m);
                    }
                }
            }
        }

        JSONObject quoted = null;
        JSONObject qsr = result.optJSONObject("quoted_status_result");
        if (qsr == null) {
            qsr = legacy.optJSONObject("quoted_status_result");
        }
        if (qsr != null) {
            quoted = qsr.optJSONObject("result");
        }
        if (quoted != null) {
            Post q = parseTweetNode(quoted, "");
            if (q != null) {
                post.quoteName = q.authorName;
                post.quoteHandle = q.handle;
                post.quoteText = q.text;
                MediaItem qm = q.firstPhoto() != null ? q.firstPhoto() : q.firstVideo();
                post.quoteThumb = qm != null ? qm.thumbUrl : "";
            }
        }
        return post;
    }

    public static MediaItem parseMedia(JSONObject m) {
        if (m == null) {
            return null;
        }
        String type = m.optString("type", "photo");
        MediaItem item = new MediaItem();
        item.thumbUrl = m.optString("media_url_https", "");
        item.title = m.optString("display_url", "");
        JSONObject info = m.optJSONObject("original_info");
        if (info != null) {
            item.width = info.optInt("width", 0);
            item.height = info.optInt("height", 0);
        }
        if ("video".equals(type) || "animated_gif".equals(type)) {
            item.type = "animated_gif".equals(type) ? MediaItem.TYPE_GIF : MediaItem.TYPE_VIDEO;
            JSONObject vi = m.optJSONObject("video_info");
            if (vi != null) {
                item.durationMs = vi.optLong("duration_millis", 0L);
                JSONArray ar = vi.optJSONArray("aspect_ratio");
                if (ar != null && ar.length() == 2 && ar.optInt(1) > 0 && (item.width <= 0 || item.height <= 0)) {
                    item.width = ar.optInt(0) * 100;
                    item.height = ar.optInt(1) * 100;
                }
                JSONArray variants = vi.optJSONArray("variants");
                if (variants != null) {
                    for (int i = 0; i < variants.length(); i++) {
                        JSONObject v = variants.optJSONObject(i);
                        if (v == null) {
                            continue;
                        }
                        String url = v.optString("url", "");
                        if (url.isEmpty()) {
                            continue;
                        }
                        item.variants.add(new MediaItem.Variant(
                                url, v.optInt("bitrate", 0), v.optString("content_type", "")));
                    }
                }
            }
            if (item.variants.isEmpty()) {
                return null;
            }
        } else {
            item.type = MediaItem.TYPE_PHOTO;
        }
        return item;
    }

    public static void fillAuthor(JSONObject tweetNode, Post post) {
        JSONObject core = tweetNode.optJSONObject("core");
        JSONObject userResults = core != null ? core.optJSONObject("user_results") : null;
        JSONObject user = userResults != null ? userResults.optJSONObject("result") : null;
        if (user == null) {
            return;
        }
        JSONObject ucore = user.optJSONObject("core");
        JSONObject ulegacy = user.optJSONObject("legacy");
        post.authorName = ucore != null ? ucore.optString("name", "") : "";
        post.handle = ucore != null ? ucore.optString("screen_name", "") : "";
        if (post.authorName.isEmpty() && ulegacy != null) {
            post.authorName = ulegacy.optString("name", "");
        }
        if (post.handle.isEmpty() && ulegacy != null) {
            post.handle = ulegacy.optString("screen_name", "");
        }
        String avatar = "";
        JSONObject av = user.optJSONObject("avatar");
        if (av != null) {
            avatar = av.optString("image_url", "");
        }
        if (avatar.isEmpty() && ulegacy != null) {
            avatar = ulegacy.optString("profile_image_url_https", "");
        }
        post.avatarUrl = bigAvatar(avatar);
        post.verified = user.optBoolean("is_blue_verified", false)
                || (user.optJSONObject("verification") != null
                && user.optJSONObject("verification").optBoolean("verified", false));
    }

    private static String nameOf(JSONObject tweetNode) {
        Post p = new Post();
        fillAuthor(tweetNode, p);
        return p.authorName;
    }

    public static String bigAvatar(String url) {
        if (url == null) {
            return "";
        }
        return url.replace("_normal.", "_200x200.").replace("_reasonably_small.", "_200x200.");
    }

    public static String unescape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static long parseDate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US);
            java.util.Date d = f.parse(raw);
            return d == null ? 0L : d.getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static List<String> iterable(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }
}
