package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SettingsSearchTest {

    private static SettingsSearch.Entry entry(String id, String en, String ko, String section, String... extra) {
        return new SettingsSearch.Entry(id, Arrays.asList(ko, en), section, section, Arrays.asList(extra));
    }

    private static final List<SettingsSearch.Entry> ENTRIES = Arrays.asList(
            entry("blur", "Line blur", "가사 흐림 효과", "lyrics_screen"),
            entry("font", "Lyrics font", "가사 글꼴", "lyrics_screen"),
            entry("status", "Hide status bar (portrait)", "상단바 숨기기 (세로)", "general"),
            entry("target", "Target language", "번역할 언어", "translation", "Spanish", "스페인어"),
            entry("translate", "Translate lyrics", "가사 번역", "translation"),
            entry("cache", "Cache size limit", "캐시 크기 제한", "lyrics_sources", "128 MB"),
            entry("like", "Double-tap to like", "두 번 탭해서 좋아요", "gestures"),
            entry("seek", "Tap lyric to seek", "가사 탭해서 이동", "gestures", "Double tap", "두 번 탭"),
            entry("ads", "Ad music style", "광고 음악 스타일", "ads", "Lofi", "Cafe jazz"),
            entry("offset", "Sync offset", "싱크 조절", "lyrics"));

    private static String top(String query) {
        List<SettingsSearch.Result> results = new SettingsSearch(ENTRIES).search(query, 5, 0);
        return results.isEmpty() ? "" : (String) results.get(0).entry.target;
    }

    @Test
    public void exactAndPrefixInEitherLanguage() {
        assertEquals("blur", top("blur"));
        assertEquals("font", top("글꼴"));
        assertEquals("target", top("target lang"));
        assertEquals("cache", top("캐시"));
        assertEquals("target", top("번역 언어"));
        assertEquals("status", top("상단 바"));         // spaced differently from the name
        assertEquals("seek", top("탭 이동"));
    }

    @Test
    public void typosStillFind() {
        assertEquals("blur", top("bluur"));
        assertEquals("cache", top("캐쉬"));            // ㅟ for ㅣ: one jamo off
        assertEquals("font", top("lyrcs font"));
        assertEquals("translate", top("tranlsate"));    // transposed letters
    }

    @Test
    public void differentWordsForTheSameIdea() {
        assertEquals("blur", top("블러"));              // the setting says 흐림 / blur
        assertEquals("font", top("폰트"));              // the setting says 글꼴 / font
        assertEquals("status", top("notification bar"));
        assertEquals("like", top("하트"));
        assertEquals("offset", top("delay"));
        assertEquals("ads", top("commercial"));
    }

    @Test
    public void relatedResultsAreMarkedAndFollowMatches() {
        List<SettingsSearch.Result> results = new SettingsSearch(ENTRIES).search("좋아요", 1, 3);
        assertEquals("like", results.get(0).entry.target);
        assertFalse(results.get(0).related);
        boolean seekRelated = false;
        for (SettingsSearch.Result r : results) {
            if ("seek".equals(r.entry.target)) seekRelated = r.related;
        }
        assertTrue(seekRelated);
    }

    @Test
    public void nothingForNothing() {
        assertTrue(new SettingsSearch(ENTRIES).search("  ", 5, 3).isEmpty());
        assertTrue(new SettingsSearch(ENTRIES).search("zzqx", 5, 3).isEmpty());
        assertTrue(new SettingsSearch(ENTRIES).search("shadow", 5, 3).isEmpty()); // "ad" inside a word
    }
}
