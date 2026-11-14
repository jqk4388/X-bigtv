package io.github.xbigtv.tv.xapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class TimelineParserTest {

    private String fixture() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("timeline_sample.json")) {
            assertNotNull("缺少 timeline_sample.json", in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void parsesTimelineWithRetweetNoteTweetAndGif() throws Exception {
        TimelinePage page = TimelineParser.parseTimeline(fixture());
        assertTrue(page.error, page.ok());
        // 3 条有效帖子，Tombstone 被跳过
        assertEquals(3, page.posts.size());
        assertEquals("DAABCgABHXFLOrA__9sKAAIdcD5Wi1ogSwgAAwAAAAIAAA", page.bottomCursor);

        // 1) 转推：作者应是被转推账号，外层名字进 retweetedBy
        Post rt = page.posts.get(0);
        assertEquals("まる", rt.authorName);
        assertEquals("Ajifry_Tail_", rt.handle);
        assertEquals("ttr◆てとら 一次創作中", rt.retweetedBy);
        assertEquals(13092, rt.likes);
        assertEquals(483934, rt.views);
        assertTrue(rt.hasVideo());
        assertEquals(0, rt.firstPhoto() == null ? 0 : 1);

        MediaItem video = rt.firstVideo();
        assertNotNull(video);
        assertEquals(MediaItem.TYPE_VIDEO, video.type);
        assertEquals(8033, video.durationMs);
        assertEquals(1280, video.width);
        assertEquals(720, video.height);
        assertNotNull("应优先拿到 HLS 地址", video.hls());
        assertTrue(video.hls().url.endsWith(".m3u8?tag=29"));
        assertEquals("0:08", video.durationText());

        // 清晰度选择
        assertEquals(2176000, video.mp4Under(Integer.MAX_VALUE).bitrate);
        assertEquals(832000, video.mp4Under(MediaItem.bitrateCap(3)).bitrate);
        assertEquals(256000, video.mp4Under(MediaItem.bitrateCap(4)).bitrate);
        assertEquals(2176000, video.mp4Under(MediaItem.bitrateCap(1)).bitrate);

        // 2) 长文 + 图片 + 引用
        Post note = page.posts.get(1);
        assertTrue(note.text.contains("note_tweet"));
        assertFalse(note.text.contains("&amp;"));
        assertEquals("ザテレビジョン", note.authorName);
        assertTrue(note.verified);
        assertEquals(12, note.likes);
        assertTrue(note.liked);
        assertFalse(note.hasVideo());
        assertNotNull(note.firstPhoto());
        assertEquals(2500, note.firstPhoto().width);
        assertEquals("引用账号", note.quoteName);
        assertTrue(note.quoteText.contains("被引用"));
        assertTrue(note.avatarUrl.contains("_200x200"));
        assertTrue(note.timeAgo().length() > 0);

        // 3) 动图
        Post gif = page.posts.get(2);
        assertEquals(MediaItem.TYPE_GIF, gif.firstVideo().type);
        assertEquals(12345, gif.views);
        assertTrue(gif.firstVideo().hls() == null);
        assertEquals("https://video.twimg.com/tweet_video/abc.mp4",
                gif.firstVideo().mp4Under(Integer.MAX_VALUE).url);
    }

    @Test
    public void featuresJsonIsValid() throws Exception {
        JSONObject o = new JSONObject(XApi.FEATURES);
        assertTrue(o.length() > 40);
        assertTrue(o.has("longform_notetweets_consumption_enabled"));
        assertTrue(o.has("view_counts_everywhere_api_enabled"));
    }

    @Test
    public void opsHaveQueryIds() {
        assertEquals("KmJCeZ__ef1fNos7tKN_fg", Ops.id("HomeTimeline"));
        assertEquals("IVA5BUbeYPTrpYekQH7jiA", Ops.id("HomeLatestTimeline"));
        assertEquals("vLHjqbDv7TPFMBkIIO1nOA", Ops.id("SearchTimeline"));
        assertEquals("dQDdKtt882nl-Km98FJGSQ", Ops.id("TweetDetail"));
    }

    @Test
    public void avatarUpgrade() {
        assertEquals("https://pbs.twimg.com/profile_images/1/abc_200x200.jpg",
                TimelineParser.bigAvatar("https://pbs.twimg.com/profile_images/1/abc_normal.jpg"));
    }
}
