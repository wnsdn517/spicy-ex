package com.eza.spicyex.settings;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds settings from whatever the user types: the name in any of the panel's languages, a word
 * from the setting's options or section, a misspelling of either, or a different word for the
 * same idea ("블러" finds "Line blur", "font" finds "글꼴", "notification bar" finds "Hide status
 * bar").
 *
 * <p>Pure Java - no Android - so it is unit-tested directly. Text is folded before matching:
 * lower case, accents stripped, and Hangul syllables decomposed into jamo, so a typo inside a
 * Korean syllable costs one edit rather than a whole mismatched character.
 *
 * <p>Scoring, per typed word, best of: substring of the entry (word start scores higher than
 * inside a word), a close misspelling of one of its words or of a word's start (Damerau-
 * Levenshtein, tolerance growing with length), or a synonym - the typed word names a concept
 * (see {@link #CONCEPTS}) the entry also mentions. Matches in the title count more than in the
 * rest. Every typed word must match something, except one when three or more are typed.
 */
public final class SettingsSearch {

    /** One searchable thing: a setting, a section, or an editor-only setting. */
    public static final class Entry {
        public final Object target;
        public final String title;
        /** Where it lives, shown under the title ("Gestures", "Layout editor"). */
        public final String place;
        /** Its section's id; related results share it. */
        public final String group;
        final String foldedTitle;
        final String foldedAll;
        final String[] titleWords;
        final String[] allWords;
        final Set<Integer> concepts;

        /**
         * @param titles the name in every language available (the first is the one shown)
         * @param extra  section names, option labels, the preference key, anything else that
         *               describes it
         */
        public Entry(Object target, List<String> titles, String place, String group, List<String> extra) {
            this.target = target;
            this.title = titles.isEmpty() ? "" : titles.get(0);
            this.place = place == null ? "" : place;
            this.group = group == null ? "" : group;
            StringBuilder t = new StringBuilder();
            for (String s : titles) t.append(' ').append(s);
            StringBuilder a = new StringBuilder(t);
            if (extra != null) for (String s : extra) a.append(' ').append(s);
            a.append(' ').append(this.place);
            foldedTitle = fold(t.toString());
            foldedAll = fold(a.toString());
            titleWords = words(foldedTitle);
            allWords = words(foldedAll);
            concepts = conceptsIn(foldedAll, allWords);
        }
    }

    public static final class Result {
        public final Entry entry;
        public final float score;
        /** Not a match itself: shown because it is close to what matched. */
        public final boolean related;

        Result(Entry entry, float score, boolean related) {
            this.entry = entry;
            this.score = score;
            this.related = related;
        }
    }

    /**
     * Words that mean the same thing here, across the panel's languages. A typed word naming any
     * member finds entries that mention any other member. Kept to the ideas this app's settings
     * actually use.
     */
    static final String[][] CONCEPTS = {
            {"blur", "블러", "흐림", "흐리게", "뿌옇게", "ぼかし", "ブラー", "размытие", "模糊"},
            {"font", "typeface", "글꼴", "폰트", "서체", "フォント", "書体", "шрифт", "字体"},
            {"size", "scale", "big", "small", "large", "크기", "사이즈", "크게", "작게", "サイズ", "大きさ", "размер", "大小"},
            {"weight", "bold", "thick", "굵기", "두께", "볼드", "太さ", "жирность", "粗细"},
            {"translation", "translate", "번역", "翻訳", "перевод", "翻译"},
            {"transliteration", "romanization", "romaji", "reading", "pronunciation", "furigana", "pinyin",
                    "발음", "로마자", "읽기", "후리가나", "병음", "읽는법", "読み", "ローマ字", "ふりがな",
                    "транслитерация", "拼音", "注音"},
            {"language", "locale", "언어", "言語", "язык", "语言"},
            {"ad", "ads", "advert", "advertisement", "commercial", "광고", "広告", "реклама", "广告"},
            {"cache", "storage", "stored", "memory", "캐시", "저장", "용량", "저장공간", "キャッシュ", "容量", "кэш", "缓存"},
            {"status bar", "statusbar", "notification bar", "system bar", "상단바", "상태바", "알림바", "시스템바",
                    "ステータスバー", "строка состояния", "状态栏"},
            {"like", "liked", "heart", "favorite", "favourite", "star", "좋아요", "하트", "별", "즐겨찾기",
                    "いいね", "ハート", "お気に入り", "избранное", "喜欢", "收藏"},
            {"seek", "jump", "skip to", "이동", "탐색", "건너뛰기", "シーク", "移動", "перемотка", "跳转"},
            {"tap", "touch", "click", "press", "double tap", "탭", "터치", "클릭", "누르기", "두번", "더블탭",
                    "タップ", "ダブルタップ", "касание", "点击", "双击"},
            {"long press", "hold", "길게", "꾹", "長押し", "удержание", "长按"},
            {"gesture", "swipe", "제스처", "스와이프", "ジェスチャー", "жест", "手势"},
            {"color", "colour", "theme", "tint", "색", "색상", "컬러", "테마", "色", "カラー", "цвет", "颜色"},
            {"background", "backdrop", "wallpaper", "배경", "배경화면", "背景", "фон"},
            {"dark", "black", "night", "어둡게", "다크", "검정", "어두운", "ダーク", "暗い", "тёмный", "深色"},
            {"animation", "motion", "effect", "transition", "애니메이션", "움직임", "효과", "전환",
                    "アニメーション", "エフェクト", "анимация", "动画", "效果"},
            {"glow", "shine", "빛", "광채", "글로우", "발광", "グロー", "свечение", "发光"},
            {"bounce", "spring", "튕김", "바운스", "스프링", "バウンス", "отскок", "弹跳"},
            {"artwork", "album art", "cover", "art", "아트워크", "앨범아트", "앨범", "표지", "커버",
                    "アートワーク", "ジャケット", "обложка", "封面"},
            {"sync", "offset", "timing", "delay", "latency", "싱크", "타이밍", "지연", "딜레이", "오프셋",
                    "同期", "タイミング", "синхронизация", "同步", "延迟"},
            {"source", "provider", "lyrics source", "제공자", "제공처", "출처", "소스", "ソース", "提供元",
                    "источник", "来源"},
            {"lyrics", "lyric", "가사", "歌詞", "текст", "歌词"},
            {"karaoke", "instrumental", "노래방", "반주", "카라오케", "カラオケ", "караоке", "卡拉ok"},
            {"now playing", "card", "mini player", "widget", "재생중", "카드", "미니플레이어", "위젯",
                    "再生中", "カード", "карточка", "卡片"},
            {"share", "공유", "共有", "поделиться", "分享"},
            {"ai", "gemini", "openai", "gpt", "deepseek", "openrouter", "llm", "인공지능", "에이아이", "ии", "人工智能"},
            {"hide", "hidden", "show", "visible", "숨기기", "숨김", "감추기", "표시", "보이기", "非表示", "表示",
                    "скрыть", "隐藏", "显示"},
            {"button", "control", "controls", "icon", "버튼", "컨트롤", "아이콘", "ボタン", "кнопка", "按钮"},
            {"spacing", "gap", "line height", "간격", "줄간격", "여백", "間隔", "интервал", "间距"},
            {"position", "place", "align", "alignment", "위치", "정렬", "배치", "位置", "配置", "позиция", "位置"},
            {"focus", "center", "centre", "포커스", "중앙", "가운데", "フォーカス", "中央", "фокус", "焦点"},
            {"connect", "remote", "device", "cast", "연결", "기기", "원격", "接続", "подключение", "连接"},
            {"music", "song", "track", "음악", "노래", "곡", "曲", "音楽", "музыка", "音乐"},
            {"mute", "silence", "volume", "sound", "음소거", "소리", "볼륨", "ミュート", "音量", "звук", "静音"},
            {"quality", "performance", "battery", "smooth", "품질", "성능", "배터리", "부드럽게", "画質", "品質",
                    "качество", "性能"},
            {"debug", "diagnostic", "log", "version", "디버그", "진단", "로그", "버전", "デバッグ", "отладка", "调试"},
            {"reset", "default", "restore", "초기화", "기본값", "リセット", "сброс", "重置"},
            {"layout", "editor", "arrange", "레이아웃", "편집", "편집기", "에디터", "レイアウト", "編集", "макет", "布局"},
    };

    private static final String[][] FOLDED_CONCEPTS = new String[CONCEPTS.length][];

    static {
        for (int i = 0; i < CONCEPTS.length; i++) {
            FOLDED_CONCEPTS[i] = new String[CONCEPTS[i].length];
            for (int j = 0; j < CONCEPTS[i].length; j++) FOLDED_CONCEPTS[i][j] = fold(CONCEPTS[i][j]);
        }
    }

    private final List<Entry> entries;

    public SettingsSearch(List<Entry> entries) {
        this.entries = entries == null ? new ArrayList<>() : entries;
    }

    /** Matches, best first, then up to {@code maxRelated} related entries marked as such. */
    public List<Result> search(String query, int maxResults, int maxRelated) {
        List<Result> out = new ArrayList<>();
        String folded = fold(query).trim();
        if (folded.isEmpty()) return out;
        String[] typed = words(folded);
        if (typed.length == 0) return out;
        Set<Integer> queryConcepts = new HashSet<>();
        for (String word : typed) queryConcepts.addAll(conceptsNamedBy(word));
        // The whole query as one phrase too ("status bar", "상단 바").
        queryConcepts.addAll(conceptsNamedBy(folded));

        List<Result> matches = new ArrayList<>();
        for (Entry entry : entries) {
            float score = score(entry, folded, typed);
            if (score > 0f) matches.add(new Result(entry, score, false));
        }
        Collections.sort(matches, (a, b) -> Float.compare(b.score, a.score));
        for (int i = 0; i < matches.size() && out.size() < maxResults; i++) out.add(matches.get(i));

        if (maxRelated > 0 && !out.isEmpty()) {
            Set<Entry> shown = new HashSet<>();
            for (Result r : out) shown.add(r.entry);
            Set<String> groups = new LinkedHashSet<>();
            groups.add(out.get(0).entry.group);
            Set<Integer> shared = new HashSet<>(queryConcepts);
            shared.addAll(out.get(0).entry.concepts);
            List<Result> related = new ArrayList<>();
            for (Entry entry : entries) {
                if (shown.contains(entry)) continue;
                float closeness = 0f;
                if (groups.contains(entry.group)) closeness += 0.3f;
                for (int c : entry.concepts) if (shared.contains(c)) closeness += 0.25f;
                if (closeness >= 0.5f) related.add(new Result(entry, closeness, true));
            }
            Collections.sort(related, (a, b) -> Float.compare(b.score, a.score));
            for (int i = 0; i < related.size() && i < maxRelated; i++) out.add(related.get(i));
        }
        return out;
    }

    static float score(Entry entry, String phrase, String[] typed) {
        float total = 0f;
        int missing = 0;
        for (String word : typed) {
            float best = Math.max(wordScore(word, entry.foldedTitle, entry.titleWords) * 1.25f,
                    wordScore(word, entry.foldedAll, entry.allWords));
            for (int c : conceptsNamedBy(word)) {
                if (entry.concepts.contains(c)) best = Math.max(best, 0.62f);
            }
            if (best <= 0f) missing++;
            total += best;
        }
        if (missing > (typed.length >= 3 ? 1 : 0)) return 0f;
        float score = total / typed.length;
        // The typed phrase whole, in the title, is the strongest signal of all.
        if (typed.length > 1 && compact(entry.foldedTitle).contains(compact(phrase))) score += 0.5f;
        for (int c : conceptsNamedBy(phrase)) if (entry.concepts.contains(c)) score = Math.max(score, 0.62f);
        return score;
    }

    /** How well one typed word matches a text: 1 at a word start, less inside, less for typos. */
    static float wordScore(String word, String text, String[] textWords) {
        if (word.isEmpty()) return 0f;
        for (String w : textWords) {
            if (w.startsWith(word)) return 1f;
        }
        if (compact(text).contains(word)) return word.length() >= 2 ? 0.8f : 0f;
        // One-letter words match only as a word start above.
        if (word.length() < 3) return 0f;
        int allowed = word.length() >= 9 ? 2 : 1;
        float best = 0f;
        for (String w : textWords) {
            if (w.length() < 2) continue;
            int d = distance(word, w);
            // A word still being typed: compare with the start of a longer word.
            if (w.length() > word.length()) d = Math.min(d, distance(word, w.substring(0, word.length())));
            if (d <= allowed) best = Math.max(best, 0.72f - 0.12f * d);
        }
        return best;
    }

    /** The concepts a typed word (or phrase) names: equal to, or the start of, a member. */
    static Set<Integer> conceptsNamedBy(String word) {
        Set<Integer> out = new HashSet<>();
        if (word.length() < 2) return out;
        String w = compact(word);
        for (int i = 0; i < FOLDED_CONCEPTS.length; i++) {
            for (String member : FOLDED_CONCEPTS[i]) {
                String m = compact(member);
                if (m.equals(w) || (w.length() >= 3 && m.startsWith(w))
                        || (w.length() >= 5 && distance(w, m) <= 1)) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    static Set<Integer> conceptsIn(String folded, String[] words) {
        Set<Integer> out = new HashSet<>();
        String flat = compact(folded);
        for (int i = 0; i < FOLDED_CONCEPTS.length; i++) {
            for (String member : FOLDED_CONCEPTS[i]) {
                String m = compact(member);
                if (m.isEmpty()) continue;
                // Short Latin members must be whole words ("ad" is not in "shadow"); anything
                // else, and all Hangul/CJK, may sit inside a longer run.
                boolean latinShort = m.length() <= 3 && m.chars().allMatch(ch -> ch < 0x80);
                boolean found = false;
                if (latinShort) {
                    for (String w : words) if (w.equals(m)) { found = true; break; }
                } else {
                    found = flat.contains(m);
                }
                if (found) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    /** Lower case, accents stripped, Hangul in jamo, punctuation to spaces. */
    public static String fold(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length() * 2);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                // Hangul syllable -> leading consonant, vowel, optional trailing consonant.
                int s = c - 0xAC00;
                out.append((char) (0x1100 + s / 588));
                out.append((char) (0x1161 + (s % 588) / 28));
                int tail = s % 28;
                if (tail != 0) out.append((char) (0x11A7 + tail));
            } else {
                out.append(c);
            }
        }
        String decomposed = Normalizer.normalize(out, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        // Hangul jamo are letters, keep them; everything not a letter or digit becomes a space.
        return decomposed.replaceAll("[^\\p{L}\\p{N}\\u1100-\\u11FF]+", " ");
    }

    private static String[] words(String folded) {
        String trimmed = folded.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split(" +");
    }

    private static String compact(String folded) {
        return folded.replace(" ", "");
    }

    /** Damerau-Levenshtein (optimal string alignment) distance. */
    static int distance(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > 2) return 3;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
            }
        }
        return d[n][m];
    }
}
