package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.xposed.XpLog;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;
import static com.eza.spicyex.lyrics.LyricUtils.spotifyUriToWebUrl;
import static com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri;

/**
 * Lyric share card: a preview sheet over the lyrics, and the image it shares.
 *
 * <p>The card is drawn in its own fixed 1080x1350 pixel space (never device dp, so text is the
 * same size on every phone). It comes in four designs - Glass, Classic, Minimal, Polaroid - over
 * one of three backdrops: the blurred artwork (the lyrics screen's own look), the artwork's
 * colours as a gradient, or the artist's photo, fetched from Spotify's Web API. Lyrics are fitted
 * by shrinking to a floor size; a selection that still would not fit is refused rather than
 * overflowing, and the card says so.
 *
 * <p>Changes animate instead of blinking: another design slides in from the side it was picked
 * on, extra lines slide the card up or down, and toggling the translation cross-fades. Cards
 * render off the main thread; the newest request wins. The shared image can carry the track's
 * Spotify Code (scannable in the Spotify app's camera search), and the share text carries the
 * track as a tappable URL - an image itself cannot hold a link.
 */
final class LyricsShareCardController {
    private static final String TAG = "[SpicyExLyricsShare]";
    private static final int W = 1080;
    /** Spotify Code width on the card. At ~300px (a quarter of the card) it was too small to read
     *  at a glance or to scan from a screenshot; these keep the title beside it readable. */
    private static final int CODE_W = 430;
    private static final int CODE_W_POLAROID = 410;
    private static final int CODE_W_CLASSIC = 380;
    private static final int H = 1350;
    // A short quote is set large enough to fill the card instead of floating in empty space.
    private static final float MAX_TEXT = 128f;
    private static final float MIN_TEXT = 34f;
    private static final String PREFS = "SpotifyPlus";
    private static final String PREF_DESIGN = "share_card_design";
    private static final String PREF_BACKDROP = "share_card_backdrop";
    private static final String PREF_CODE = "share_card_spotify_code";

    enum Design { GLASS, CLASSIC, MINIMAL, POLAROID, POSTER, VINYL, TICKET, SPOTLIGHT }
    enum Backdrop { BLUR, COLOR, ARTIST }

    /** Renders the lyrics screen's own background at a given size. */
    interface BackgroundSnapshot {
        Bitmap render(int width, int height);
    }
    /** SLIDE_UP/DOWN and TEXT move only the lyric text layer; the rest swap the whole card. */
    private enum Transition { SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN, FADE, TEXT }

    private static final ExecutorService RENDER = Executors.newSingleThreadExecutor();
    private static final java.util.Map<String, Bitmap> ARTIST_IMAGES = new java.util.LinkedHashMap<>();
    /** Spotify Code images by "uri|dark" - bars white on black, or black on white for paper. */
    private static final java.util.Map<String, Bitmap> SPOTIFY_CODES = new java.util.LinkedHashMap<>();

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final SettingsUiStrings strings;
    private View overlay;
    private FrameLayout cardHost;
    /** The card on screen: its artwork/chrome layer and, above it, the lyric text layer. */
    private FrameLayout currentCard;
    private ImageView currentBase;
    /** The lyric text over the card, one view per lyric line (with its translation). */
    private FrameLayout currentTextLayer;
    private Map<Integer, ImageView> pieceViews = new java.util.HashMap<>();
    private Map<Integer, Piece> pieceData = new java.util.HashMap<>();

    /** One lyric line (and its translation) as set on the card: its image and where it sits. */
    private static final class Piece {
        final int id;
        final Bitmap bitmap;
        final float x;
        final float y;
        final float size;

        Piece(int id, Bitmap bitmap, float x, float y, float size) {
            this.id = id;
            this.bitmap = bitmap;
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }
    // Carousel: the neighbouring designs peek in at both edges, so sideways swiping is visible
    // without a row of design chips.
    private ImageView peekPrev;
    private ImageView peekNext;
    private int peekGeneration;
    private Bitmap currentBitmap;
    private TextView hint;
    private final List<TextView> backdropChips = new ArrayList<>();
    private TextView codeChip;
    private TextView translationChip;
    /** Top bar: the current design's name. */
    private TextView designLabel;
    // Design strip: a small live render of every design, the current one ringed.
    private HorizontalScrollView designStrip;
    private final List<ImageView> designThumbs = new ArrayList<>();
    private final List<View> designFrames = new ArrayList<>();
    private final List<TextView> designNames = new ArrayList<>();
    private volatile int thumbGeneration;
    private LyricsDocument document;
    private int startIndex, endIndex;
    private boolean showTranslation = true;
    private SpotifyTrack track;
    private Bitmap artwork;
    private Bitmap artistImage;
    private ViewGroup root;
    private Design design;
    private Backdrop backdrop;
    private boolean spotifyCode;
    private int generation;
    private BackgroundSnapshot backgroundSnapshot;
    /** The lyrics background frozen when the sheet opened; the "lyrics background" backdrop. */
    private Bitmap lyricsBackground;

    void setBackgroundSnapshot(BackgroundSnapshot snapshot) {
        backgroundSnapshot = snapshot;
    }

    LyricsShareCardController(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.strings = com.eza.spicyex.UiLanguage.strings(activity,
                com.eza.spicyex.SpotifyPlusConfig.from(activity).get(com.eza.spicyex.Settings.UI_LANGUAGE));
        this.design = enumOr(Design.class, prefs.getString(PREF_DESIGN, null), Design.GLASS);
        this.backdrop = enumOr(Backdrop.class, prefs.getString(PREF_BACKDROP, null), Backdrop.BLUR);
        this.spotifyCode = prefs.getBoolean(PREF_CODE, false);
    }

    /** Long-pressed a real lyric line: quote it, with its translation underneath if present. */
    /** The lyric row that was pressed; its text flies into the card as the sheet opens. */
    private View sourceRow;

    void showForLine(ViewGroup root, LyricsDocument doc, SpotifyTrack track, Bitmap art, int index,
                     View sourceRow) {
        this.sourceRow = sourceRow;
        showForLine(root, doc, track, art, index);
    }

    void showForLine(ViewGroup root, LyricsDocument doc, SpotifyTrack track, Bitmap art, int index) {
        this.root = root;
        this.document = doc;
        this.track = track;
        this.artwork = art;
        this.startIndex = index;
        this.endIndex = index;
        this.showTranslation = true;
        show();
    }

    /** No lyrics for this track at all: fall back to a plain "now playing" track card. */
    void showTrackCard(ViewGroup root, SpotifyTrack track, Bitmap art) {
        this.sourceRow = null;
        this.root = root;
        this.document = null;
        this.track = track;
        this.artwork = art;
        this.startIndex = -1;
        this.endIndex = -1;
        this.showTranslation = false;
        show();
    }

    private String s(String key, String fallback) {
        return strings.get("share_" + key, fallback);
    }

    private void show() {
        if (activity == null || root == null || track == null) return;
        dismiss();
        artistImage = cachedArtist(track);
        lyricsBackground = null;
        overlay = buildPreview();
        // Above the lyric screen's own chrome, which is raised with elevation and otherwise draws
        // through the sheet regardless of child order.
        overlay.setElevation(dp(64));
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        BackgroundSnapshot snapshot = backgroundSnapshot;
        if (snapshot != null) {
            // Off the main thread; the card redraws with it as soon as it is ready.
            RENDER.execute(() -> {
                Bitmap frame = snapshot.render(W, H);
                main.post(() -> {
                    if (overlay == null || frame == null) return;
                    lyricsBackground = frame;
                    if (backdrop == Backdrop.COLOR) {
                        render(Transition.FADE);
                        renderThumbs();
                    }
                });
            });
        }
        render(Transition.FADE);
        renderThumbs();
        if (backdrop == Backdrop.ARTIST && artistImage == null) fetchArtistImage();
        if (spotifyCode) fetchSpotifyCode();
        if (document != null && startIndex >= 0) {
            View teaseHost = overlay;
            teaseHost.postDelayed(() -> {
                if (overlay == teaseHost) teaseNextLine();
            }, row0Delay());
        }
        View row = sourceRow;
        sourceRow = null;
        if (row != null && document != null && row.isAttachedToWindow()) {
            View host = overlay;
            host.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    host.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (overlay == host) flyQuoteIn(row);
                    return true;
                }
            });
        }
    }

    /**
     * The pressed lyric lifts out of the list and glides into the card: a snapshot of the row
     * travels from where it sits on screen to where the quote is set in the card, scaled to the
     * card's type size, and dissolves as the card itself fades in underneath it.
     */
    private void flyQuoteIn(View row) {
        try {
            if (!(overlay instanceof ViewGroup) || cardHost == null || cardHost.getWidth() <= 0
                    || row.getWidth() <= 0 || row.getHeight() <= 0) return;
            TextView text = firstText(row);
            if (text == null || text.getTextSize() <= 0f) return;
            List<String> quotes = collectQuotes(startIndex, endIndex);
            if (quotes.isEmpty()) return;
            TextBox box = textBox(design, true);
            Fitted fitted = layoutLyrics(quotes, collectTranslations(startIndex, endIndex), box, false);
            if (fitted.main.isEmpty()) return;
            float cardScale = cardHost.getWidth() / (float) W;
            float quoteTop = quoteTop(design, box, fitted.height);
            float ghostScale = fitted.main.get(0).getPaint().getTextSize() * cardScale / text.getTextSize();

            Bitmap snapshot = Bitmap.createBitmap(row.getWidth(), row.getHeight(), Bitmap.Config.ARGB_8888);
            row.draw(new Canvas(snapshot));

            int[] overlayLoc = new int[2];
            int[] rowLoc = new int[2];
            int[] textLoc = new int[2];
            int[] cardLoc = new int[2];
            overlay.getLocationOnScreen(overlayLoc);
            row.getLocationOnScreen(rowLoc);
            text.getLocationOnScreen(textLoc);
            cardHost.getLocationOnScreen(cardLoc);
            // The sheet itself is still sliding up; aim for where the card will come to rest.
            View column = (View) cardHost.getParent();
            float settle = column == null ? 0f : column.getTranslationY();
            float textOffX = textLoc[0] - rowLoc[0] + text.getTotalPaddingLeft();
            float textOffY = textLoc[1] - rowLoc[1] + text.getTotalPaddingTop();
            float targetX = cardLoc[0] - overlayLoc[0] + box.left * cardScale - textOffX * ghostScale;
            float targetY = cardLoc[1] - settle - overlayLoc[1] + quoteTop * cardScale - textOffY * ghostScale;

            ImageView ghost = new ImageView(activity);
            ghost.setImageBitmap(snapshot);
            ghost.setPivotX(0f);
            ghost.setPivotY(0f);
            ((ViewGroup) overlay).addView(ghost, new FrameLayout.LayoutParams(row.getWidth(), row.getHeight()));
            ghost.setX(rowLoc[0] - overlayLoc[0]);
            ghost.setY(rowLoc[1] - overlayLoc[1]);
            ghost.setElevation(dp(2));

            cardHost.setAlpha(0f);
            cardHost.animate().alpha(1f).setStartDelay(320).setDuration(360)
                    .setInterpolator(new PathInterpolator(0.3f, 0f, 0.2f, 1f)).start();
            ghost.animate().x(targetX).y(targetY).scaleX(ghostScale).scaleY(ghostScale)
                    .setDuration(560).setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f))
                    .withEndAction(() -> ghost.animate().alpha(0f).setDuration(220)
                            .withEndAction(() -> {
                                if (ghost.getParent() instanceof ViewGroup) {
                                    ((ViewGroup) ghost.getParent()).removeView(ghost);
                                }
                            }).start())
                    .start();
        } catch (Throwable error) {
            XpLog.log(TAG + " quote fly-in skipped: " + error);
        }
    }

    /** After the quote has landed (fly-in ~800ms), or sooner when there was none. */
    private long row0Delay() {
        return sourceRow != null ? 1150L : 650L;
    }

    /**
     * Gesture hint: the next lyric peeks up from the bottom of the card as if about to be pulled
     * in, the card lifts a touch to make room, then both settle back - "swipe up to add".
     */
    private void teaseNextLine() {
        if (document == null || cardHost == null || !(cardHost.getParent() instanceof FrameLayout)) return;
        int next = endIndex;
        while (next < document.appliedLines.size() - 1) {
            next++;
            if (!isBlankLine(next)) break;
        }
        if (next == endIndex || isBlankLine(next)) return;
        List<String> candidate = collectQuotes(startIndex, next);
        if (!withinLineBudget(candidate) || !fits(candidate, collectTranslations(startIndex, next))) return;
        FrameLayout carousel = (FrameLayout) cardHost.getParent();
        TextView peek = new TextView(activity);
        peek.setText("\u2191  " + safe(document.appliedLines.get(next).text));
        peek.setTextColor(Color.WHITE);
        peek.setTextSize(15);
        peek.setTypeface(Typeface.DEFAULT_BOLD);
        peek.setSingleLine(true);
        peek.setEllipsize(TextUtils.TruncateAt.END);
        peek.setPadding(dp(16), dp(10), dp(16), dp(10));
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(Color.argb(150, 20, 20, 24));
        pill.setCornerRadius(dp(22));
        peek.setBackground(pill);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(18);
        peek.setMaxWidth(Math.max(dp(120), cardHost.getWidth() - dp(48)));
        carousel.addView(peek, lp);
        peek.setAlpha(0f);
        peek.setTranslationY(dp(26));
        PathInterpolator ease = new PathInterpolator(0.3f, 0f, 0.2f, 1f);
        cardHost.animate().translationY(-dp(10)).setDuration(420).setInterpolator(ease).start();
        peek.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(ease)
                .withEndAction(() -> peek.postDelayed(() -> {
                    if (cardHost != null) {
                        cardHost.animate().translationY(0f).setDuration(380).setInterpolator(ease).start();
                    }
                    peek.animate().alpha(0f).translationY(dp(26)).setDuration(380).setInterpolator(ease)
                            .withEndAction(() -> {
                                if (peek.getParent() instanceof ViewGroup) {
                                    ((ViewGroup) peek.getParent()).removeView(peek);
                                }
                            }).start();
                }, 900)).start();
    }

    private static TextView firstText(View view) {
        if (view instanceof TextView && ((TextView) view).getText().length() > 0
                && view.getVisibility() == View.VISIBLE) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = firstText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    boolean isShowing() {
        return overlay != null;
    }

    void dismiss() {
        generation++;
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            View leaving = overlay;
            leaving.animate().alpha(0f).setDuration(180)
                    .withEndAction(() -> {
                        if (leaving.getParent() instanceof ViewGroup) {
                            ((ViewGroup) leaving.getParent()).removeView(leaving);
                        }
                    }).start();
        }
        overlay = null;
        cardHost = null;
        currentCard = null;
        thumbGeneration++;
        designThumbs.clear();
        designFrames.clear();
        designNames.clear();
        designStrip = null;
        designLabel = null;
        translationChip = null;
        peekPrev = null;
        peekNext = null;
        currentTextLayer = null;
        pieceViews = new java.util.HashMap<>();
        pieceData = new java.util.HashMap<>();
    }

    // ---------------------------------------------------------------- preview UI

    private View buildPreview() {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(Color.argb(220, 0, 0, 0));
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> dismiss());

        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int screenW = root.getWidth() > 0 ? root.getWidth() : metrics.widthPixels;
        int screenH = root.getHeight() > 0 ? root.getHeight() : metrics.heightPixels;
        int insetTop = 0;
        int insetBottom = 0;
        int insetLeft = 0;
        int insetRight = 0;
        android.view.WindowInsets insets = root.getRootWindowInsets();
        if (insets != null) {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
            insetLeft = insets.getSystemWindowInsetLeft();
            insetRight = insets.getSystemWindowInsetRight();
        }
        // Landscape puts the controls in a side panel so the card keeps the screen's height.
        boolean landscape = screenW > screenH;

        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.setPadding(insetLeft, insetTop, landscape ? insetRight : 0, landscape ? insetBottom : 0);

        int panelW = landscape ? Math.min(dp(400), Math.round(screenW * 0.46f)) : screenW;
        View panel = buildPanel(landscape, landscape ? 0 : insetBottom);
        int panelH = 0;
        if (!landscape) {
            panel.measure(View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            panelH = panel.getMeasuredHeight();
        }

        LinearLayout stageColumn = new LinearLayout(activity);
        stageColumn.setOrientation(LinearLayout.VERTICAL);
        // Not clickable: taps around the card fall through to the scrim and close the sheet.
        stageColumn.addView(topBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        // As large as the room between the top bar and the controls allows.
        int availW = landscape ? screenW - panelW - insetLeft - insetRight : screenW;
        int availH = screenH - insetTop - insetBottom - dp(56 + 34 + 12) - panelH;
        int cardWidthPx = Math.round(Math.min(availW * (landscape ? 0.84f : 0.80f),
                Math.max(dp(160), availH) * (float) W / H));
        int cardHeightPx = cardWidthPx * H / W;

        FrameLayout stage = new FrameLayout(activity);
        FrameLayout carousel = new FrameLayout(activity);
        carousel.setClipChildren(false);
        float peekOffset = cardWidthPx + dp(14);
        peekPrev = peekView(-peekOffset);
        peekNext = peekView(peekOffset);
        peekPrev.setOnClickListener(v -> stepDesign(-1));
        peekNext.setOnClickListener(v -> stepDesign(1));
        carousel.addView(peekPrev, new FrameLayout.LayoutParams(cardWidthPx, cardHeightPx, Gravity.CENTER));
        carousel.addView(peekNext, new FrameLayout.LayoutParams(cardWidthPx, cardHeightPx, Gravity.CENTER));
        cardHost = new FrameLayout(activity);
        cardHost.setClipChildren(true);
        cardHost.setElevation(dp(8));
        cardHost.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                // The card's own rounded corners (64 of 1080), for its shadow.
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        view.getWidth() * 64f / W);
            }
        });
        carousel.addView(cardHost, new FrameLayout.LayoutParams(cardWidthPx, cardHeightPx, Gravity.CENTER));
        stage.addView(carousel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardHeightPx, Gravity.CENTER));
        stageColumn.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        installPreviewGestures(cardHost);

        hint = new TextView(activity);
        hint.setTextColor(Color.argb(140, 255, 255, 255));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setText(s("hint", "Swipe up to add lyrics · Tap to toggle translation"));
        hint.setVisibility(document == null ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(8);
        hintLp.bottomMargin = dp(12);
        stageColumn.addView(hint, hintLp);

        if (landscape) {
            page.addView(stageColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            page.addView(panel, new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            page.addView(stageColumn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            page.addView(panel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        scrim.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateDesignChrome(false);

        // The card rises into place (the quote fly-in aims for where it comes to rest) while the
        // controls slide in from their edge.
        PathInterpolator ease = new PathInterpolator(0.2f, 0.9f, 0.2f, 1f);
        carousel.setTranslationY(dp(40));
        panel.setAlpha(0f);
        if (landscape) panel.setTranslationX(dp(60));
        else panel.setTranslationY(dp(80));
        scrim.post(() -> {
            scrim.animate().alpha(1f).setDuration(220).start();
            carousel.animate().translationY(0f).setDuration(420).setInterpolator(ease).start();
            panel.animate().translationX(0f).translationY(0f).alpha(1f).setStartDelay(60)
                    .setDuration(420).setInterpolator(ease).start();
        });
        return scrim;
    }

    /** Close on the left, the current design's name in the middle. */
    private View topBar() {
        FrameLayout bar = new FrameLayout(activity);
        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextSize(15);
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(s("close", "Close"));
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(Color.argb(46, 255, 255, 255));
        close.setBackground(circle);
        close.setOnClickListener(v -> dismiss());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(40), dp(40),
                Gravity.START | Gravity.CENTER_VERTICAL);
        closeLp.leftMargin = dp(14);
        bar.addView(close, closeLp);

        designLabel = new TextView(activity);
        designLabel.setTextColor(Color.WHITE);
        designLabel.setTextSize(16);
        designLabel.setTypeface(Typeface.DEFAULT_BOLD);
        designLabel.setGravity(Gravity.CENTER);
        bar.addView(designLabel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return bar;
    }

    /**
     * The controls sheet: every design as a live thumbnail, the backdrop as a segmented control
     * with the Spotify Code and translation toggles beside it, then Save and Share.
     */
    private View buildPanel(boolean landscape, int bottomInset) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(landscape ? 18 : 10), 0, dp(18) + bottomInset);
        if (!landscape) {
            View grabber = new View(activity);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(Color.argb(70, 255, 255, 255));
            g.setCornerRadius(dp(2));
            grabber.setBackground(g);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(36), dp(4));
            glp.gravity = Gravity.CENTER_HORIZONTAL;
            glp.bottomMargin = dp(6);
            content.addView(grabber, glp);
        }

        content.addView(sectionLabel(s("design_label", "Design")));
        designStrip = new HorizontalScrollView(activity);
        designStrip.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(activity);
        strip.setPadding(dp(14), 0, dp(14), 0);
        designThumbs.clear();
        designFrames.clear();
        designNames.clear();
        for (Design d : Design.values()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (d.ordinal() > 0) lp.leftMargin = dp(8);
            strip.addView(designItem(d), lp);
        }
        designStrip.addView(strip);
        content.addView(designStrip);

        content.addView(sectionLabel(s("backdrop_label", "Background")));
        HorizontalScrollView optionsScroll = new HorizontalScrollView(activity);
        optionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout options = new LinearLayout(activity);
        options.setGravity(Gravity.CENTER_VERTICAL);
        options.setPadding(dp(16), 0, dp(16), 0);
        String[] backdropLabels = {s("backdrop_blur", "Blurred artwork"), s("backdrop_lyrics", "Lyrics background"),
                s("backdrop_artist", "Artist photo")};
        options.addView(segmented(backdropLabels, backdropChips, backdrop.ordinal(), index -> {
            Backdrop next = Backdrop.values()[index];
            if (next == backdrop) return;
            backdrop = next;
            prefs.edit().putString(PREF_BACKDROP, backdrop.name()).apply();
            render(Transition.FADE);
            renderThumbs();
            if (backdrop == Backdrop.ARTIST && artistImage == null) fetchArtistImage();
        }));
        codeChip = toggleChip(s("spotify_code", "Spotify Code"), spotifyCode);
        codeChip.setOnClickListener(v -> {
            spotifyCode = !spotifyCode;
            prefs.edit().putBoolean(PREF_CODE, spotifyCode).apply();
            styleToggle(codeChip, spotifyCode);
            trimSelectionToFit();
            render(Transition.FADE);
            if (spotifyCode) fetchSpotifyCode();
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleLp.leftMargin = dp(10);
        options.addView(codeChip, toggleLp);
        if (hasAnyTranslation()) {
            translationChip = toggleChip(s("translation", "Translation"), showTranslation);
            translationChip.setOnClickListener(v -> toggleTranslation());
            LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            trLp.leftMargin = dp(6);
            options.addView(translationChip, trLp);
        }
        optionsScroll.addView(options);
        content.addView(optionsScroll);

        // Share targets, as in Spotify's own sheet: the story apps that are installed (with their
        // own icons), then copy link, save and the system sheet.
        content.addView(sectionLabel(s("share_to", "Share to")));
        HorizontalScrollView targetsScroll = new HorizontalScrollView(activity);
        targetsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout targets = new LinearLayout(activity);
        targets.setPadding(dp(12), 0, dp(12), 0);
        if (canShareTo(StoryTarget.INSTAGRAM)) {
            targets.addView(shareTarget(appIcon(StoryTarget.INSTAGRAM.pkg), null,
                    s("instagram_story", "Instagram Stories"), v -> shareToStory(StoryTarget.INSTAGRAM)));
        }
        if (canShareTo(StoryTarget.FACEBOOK)) {
            targets.addView(shareTarget(appIcon(StoryTarget.FACEBOOK.pkg), null,
                    s("facebook_story", "Facebook Stories"), v -> shareToStory(StoryTarget.FACEBOOK)));
        }
        if (!isBlank(webLink(track))) {
            targets.addView(shareTarget(null, "🔗", s("copy_link", "Copy link"), v -> copyLink()));
        }
        targets.addView(shareTarget(null, "↓", s("save", "Save"), v -> saveOnly()));
        targets.addView(shareTarget(null, "•••", s("more", "More"), v -> {
            shareCard();
            dismiss();
        }));
        targetsScroll.addView(targets);
        content.addView(targetsScroll);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(242, 24, 24, 27));
        float r = dp(26);
        bg.setCornerRadii(landscape ? new float[]{r, r, 0, 0, 0, 0, r, r}
                : new float[]{r, r, r, r, 0, 0, 0, 0});
        // Taps on the sheet stay on it instead of closing everything.
        if (landscape) {
            android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setBackground(bg);
            scroll.setClickable(true);
            content.setGravity(Gravity.CENTER_VERTICAL);
            scroll.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return scroll;
        }
        content.setBackground(bg);
        content.setClickable(true);
        return content;
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(11);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.08f);
        label.setTextColor(Color.argb(130, 255, 255, 255));
        label.setPadding(dp(20), dp(12), dp(20), dp(8));
        return label;
    }

    private View designItem(Design d) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout frame = new FrameLayout(activity);
        frame.setPadding(dp(3), dp(3), dp(3), dp(3));
        ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable placeholder = new android.graphics.drawable.GradientDrawable();
        placeholder.setColor(Color.argb(36, 255, 255, 255));
        placeholder.setCornerRadius(dp(8));
        thumb.setBackground(placeholder);
        thumb.setClipToOutline(true);
        frame.addView(thumb, new FrameLayout.LayoutParams(dp(58), dp(72)));
        item.addView(frame);
        TextView name = new TextView(activity);
        name.setText(designName(d));
        name.setTextSize(11);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(4);
        item.addView(name, nameLp);
        item.setOnClickListener(v -> selectDesign(d));
        designThumbs.add(thumb);
        designFrames.add(frame);
        designNames.add(name);
        return item;
    }

    private String designName(Design d) {
        String key = d.name().toLowerCase(java.util.Locale.ROOT);
        return s("design_" + key, d.name().charAt(0) + key.substring(1));
    }

    /** Title, ring on the current thumbnail, and the strip scrolled to show it. */
    private void updateDesignChrome(boolean animate) {
        if (designLabel != null) designLabel.setText(designName(design));
        for (int i = 0; i < designFrames.size(); i++) {
            boolean selected = i == design.ordinal();
            if (selected) {
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setCornerRadius(dp(11));
                ring.setStroke(dp(2), Color.WHITE);
                designFrames.get(i).setBackground(ring);
            } else {
                designFrames.get(i).setBackground(null);
            }
            TextView name = designNames.get(i);
            name.setTextColor(selected ? Color.WHITE : Color.argb(140, 255, 255, 255));
            name.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        HorizontalScrollView strip = designStrip;
        int index = design.ordinal();
        if (strip == null || index >= designFrames.size()) return;
        strip.post(() -> {
            View item = (View) designFrames.get(Math.min(index, designFrames.size() - 1)).getParent();
            if (item == null) return;
            int x = item.getLeft() + item.getWidth() / 2 - strip.getWidth() / 2;
            if (animate) strip.smoothScrollTo(Math.max(0, x), 0);
            else strip.scrollTo(Math.max(0, x), 0);
        });
    }

    /**
     * Every design rendered small with the current content, one at a time on the render thread
     * (after the card itself), so the strip shows what each would actually look like.
     */
    private void renderThumbs() {
        if (designThumbs.isEmpty() || track == null) return;
        int token = ++thumbGeneration;
        List<String> quotes = collectQuotes(startIndex, endIndex);
        List<String> translations = collectTranslations(startIndex, endIndex);
        Backdrop b = backdrop;
        Bitmap art = artwork;
        Bitmap artist = artistImage;
        Bitmap lyricsBg = lyricsBackground;
        SpotifyTrack t = track;
        RENDER.execute(() -> {
            for (Design d : Design.values()) {
                if (token != thumbGeneration) return;
                Bitmap small;
                try {
                    Bitmap full = renderCard(d, b, null, art, artist, lyricsBg, quotes, translations,
                            safe(t.title), safe(t.artist));
                    small = Bitmap.createScaledBitmap(full, W / 6, H / 6, true);
                    if (small != full) full.recycle();
                } catch (Throwable error) {
                    continue;
                }
                int index = d.ordinal();
                main.post(() -> {
                    if (token != thumbGeneration || index >= designThumbs.size()) return;
                    designThumbs.get(index).setImageBitmap(small);
                });
            }
        });
    }

    private boolean hasAnyTranslation() {
        if (document == null) return false;
        for (AppliedLine line : document.appliedLines) {
            if (!line.dotLine && !isBlank(line.translatedText)) return true;
        }
        return false;
    }

    private void toggleTranslation() {
        if (document == null) return;
        showTranslation = !showTranslation;
        if (translationChip != null) styleToggle(translationChip, showTranslation);
        render(Transition.TEXT);
    }

    private interface IndexListener {
        void onPick(int index);
    }

    /** One pill holding the choices; the chosen one is a white segment inside it. */
    private LinearLayout segmented(String[] labels, List<TextView> segments, int selected,
                                   IndexListener listener) {
        LinearLayout group = new LinearLayout(activity);
        group.setPadding(dp(3), dp(3), dp(3), dp(3));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(34, 255, 255, 255));
        bg.setCornerRadius(dp(20));
        group.setBackground(bg);
        segments.clear();
        for (int i = 0; i < labels.length; i++) {
            TextView segment = new TextView(activity);
            segment.setText(labels[i]);
            segment.setTextSize(13);
            segment.setSingleLine(true);
            segment.setGravity(Gravity.CENTER);
            segment.setPadding(dp(14), dp(7), dp(14), dp(7));
            styleSegment(segment, i == selected);
            final int index = i;
            segment.setOnClickListener(v -> {
                for (int j = 0; j < segments.size(); j++) styleSegment(segments.get(j), j == index);
                listener.onPick(index);
            });
            segments.add(segment);
            group.addView(segment);
        }
        return group;
    }

    private void styleSegment(TextView segment, boolean selected) {
        if (selected) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(17));
            bg.setColor(Color.WHITE);
            segment.setBackground(bg);
        } else {
            segment.setBackground(null);
        }
        segment.setTextColor(selected ? Color.BLACK : Color.argb(210, 255, 255, 255));
        segment.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private TextView toggleChip(String label, boolean on) {
        TextView chip = new TextView(activity);
        chip.setTag(label);
        chip.setTextSize(13);
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), dp(10), dp(14), dp(10));
        styleToggle(chip, on);
        return chip;
    }

    /** On: Spotify green with a check; off: an outline. */
    private void styleToggle(TextView chip, boolean on) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(20));
        if (on) {
            bg.setColor(Color.rgb(30, 215, 96));
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), Color.argb(80, 255, 255, 255));
        }
        chip.setBackground(bg);
        chip.setText((on ? "✓ " : "") + chip.getTag());
        chip.setTextColor(on ? Color.BLACK : Color.argb(220, 255, 255, 255));
        chip.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private android.graphics.drawable.Drawable appIcon(String pkg) {
        try {
            return activity.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable error) {
            return null;
        }
    }

    /** A round button (an app's icon, or a glyph on a light disc) over its label. */
    private View shareTarget(android.graphics.drawable.Drawable icon, String glyph, String label,
                             View.OnClickListener onClick) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(6), dp(2), dp(6), dp(2));
        View button;
        if (icon != null) {
            ImageView image = new ImageView(activity);
            image.setImageDrawable(icon);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            button = image;
        } else {
            TextView text = new TextView(activity);
            text.setText(glyph);
            text.setTextSize(18);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextColor(Color.WHITE);
            text.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
            disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            disc.setColor(Color.argb(46, 255, 255, 255));
            text.setBackground(disc);
            button = text;
        }
        item.addView(button, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView name = new TextView(activity);
        name.setText(label);
        name.setTextSize(11);
        name.setTextColor(Color.argb(210, 255, 255, 255));
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setWidth(dp(72));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(6);
        item.addView(name, nameLp);
        item.setOnClickListener(onClick);
        return item;
    }

    private void installPreviewGestures(View view) {
        android.view.GestureDetector detector = new android.view.GestureDetector(activity,
                new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(android.view.MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                toggleTranslation();
                return true;
            }

            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                   float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    // Sideways: previous / next design (the carousel wraps around).
                    stepDesign(dx < 0 ? 1 : -1);
                    return true;
                }
                if (document == null || startIndex < 0) return false;
                // A press-and-drag already stepped through lines as it went (see below).
                if (dragStepped) return true;
                // Swipe up pulls the next lyric up into the card; swipe down brings back the one
                // before.
                if (!expandSelection(dy < 0)) bounceText(dy < 0);
                return true;
            }
        });
        int touchSlop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        view.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            handleDrag(event, touchSlop);
            return true;
        });
    }

    // Press-and-drag: holding and pulling up (or down) keeps bringing lines in, one per
    // DRAG_STEP_DP of travel, with the text following the finger in between.
    private static final int DRAG_STEP_DP = 64;
    private float dragDownX;
    private float dragDownY;
    private float dragAnchorY;
    private boolean dragging;
    private boolean dragStepped;
    private boolean dragHitEdge;

    private void handleDrag(android.view.MotionEvent event, int touchSlop) {
        if (document == null || startIndex < 0) return;
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                dragDownX = event.getX();
                dragDownY = event.getY();
                dragAnchorY = event.getY();
                dragging = false;
                dragStepped = false;
                dragHitEdge = false;
                break;
            case android.view.MotionEvent.ACTION_MOVE: {
                float totalX = event.getX() - dragDownX;
                float totalY = event.getY() - dragDownY;
                if (!dragging) {
                    if (Math.abs(totalY) > touchSlop && Math.abs(totalY) > Math.abs(totalX) * 1.2f) {
                        dragging = true;
                        dragAnchorY = event.getY();
                    } else {
                        break;
                    }
                }
                float dy = event.getY() - dragAnchorY;
                if (Math.abs(dy) >= dp(DRAG_STEP_DP) && !dragHitEdge) {
                    boolean up = dy < 0;
                    if (expandSelection(up)) {
                        dragStepped = true;
                        dragAnchorY = event.getY();
                        dy = 0f;
                    } else {
                        dragHitEdge = true;
                    }
                }
                // The text leans after the finger, stiffening as it goes (harder at the edge).
                followDrag(rubber(dy, dragHitEdge ? dp(26) : dp(40)));
                break;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    if (dragHitEdge) bounceText(event.getY() - dragAnchorY < 0);
                    else settleText();
                }
                dragging = false;
                break;
            default:
                break;
        }
    }

    private static float rubber(float distance, float limit) {
        float sign = Math.signum(distance);
        float x = Math.abs(distance);
        return sign * limit * (1f - 1f / (x / limit * 0.55f + 1f));
    }

    private void followDrag(float offset) {
        if (currentTextLayer == null) return;
        currentTextLayer.animate().cancel();
        currentTextLayer.setTranslationY(offset);
    }

    private void settleText() {
        if (currentTextLayer == null) return;
        currentTextLayer.animate().translationY(0f).setDuration(320)
                .setInterpolator(new PathInterpolator(0.2f, 0.9f, 0.2f, 1f)).start();
    }

    /**
     * Nothing more that way (the song's first or last line): the text reaches for a next line
     * that is not there and springs back, instead of the gesture silently doing nothing.
     */
    private void bounceText(boolean up) {
        if (currentTextLayer == null) return;
        FrameLayout layer = currentTextLayer;
        float reach = (up ? -1f : 1f) * dp(22);
        float from = layer.getTranslationY();
        layer.animate().cancel();
        layer.animate().translationY(Math.abs(from) > Math.abs(reach) ? from : reach)
                .setDuration(140).setInterpolator(new PathInterpolator(0.3f, 0f, 0.4f, 1f))
                .withEndAction(() -> layer.animate().translationY(0f).setDuration(520)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start())
                .start();
    }

    private ImageView peekView(float offsetX) {
        ImageView view = new ImageView(activity);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setTranslationX(offsetX);
        view.setScaleX(0.9f);
        view.setScaleY(0.9f);
        view.setAlpha(0.45f);
        return view;
    }

    private static Design designAt(int ordinal) {
        int n = Design.values().length;
        return Design.values()[((ordinal % n) + n) % n];
    }

    /** Moves the carousel one design left (-1) or right (+1). */
    private void stepDesign(int direction) {
        applyDesign(designAt(design.ordinal() + direction), direction);
    }

    /** Picked from the strip: slides the way the strip reads. */
    private void selectDesign(Design next) {
        if (next == design) return;
        applyDesign(next, next.ordinal() > design.ordinal() ? 1 : -1);
    }

    private void applyDesign(Design next, int direction) {
        design = next;
        prefs.edit().putString(PREF_DESIGN, design.name()).apply();
        trimSelectionToFit();
        render(direction > 0 ? Transition.SLIDE_LEFT : Transition.SLIDE_RIGHT);
        updateDesignChrome(true);
    }

    /** Renders the neighbouring designs into the peek views (same content, small cost). */
    private void renderPeeks() {
        if (peekPrev == null || peekNext == null) return;
        int token = ++peekGeneration;
        List<String> quotes = collectQuotes(startIndex, endIndex);
        List<String> translations = collectTranslations(startIndex, endIndex);
        Design prev = designAt(design.ordinal() - 1);
        Design next = designAt(design.ordinal() + 1);
        Backdrop b = backdrop;
        Bitmap art = artwork;
        Bitmap artist = artistImage;
        Bitmap lyricsBg = lyricsBackground;
        SpotifyTrack t = track;
        RENDER.execute(() -> {
            Bitmap left;
            Bitmap right;
            try {
                left = renderCard(prev, b, null, art, artist, lyricsBg, quotes, translations,
                        safe(t.title), safe(t.artist));
                right = renderCard(next, b, null, art, artist, lyricsBg, quotes, translations,
                        safe(t.title), safe(t.artist));
            } catch (Throwable error) {
                return;
            }
            main.post(() -> {
                if (token != peekGeneration || peekPrev == null) return;
                peekPrev.setImageBitmap(left);
                peekNext.setImageBitmap(right);
            });
        });
    }

    /** Adds the next line below (swipe down) or the previous one above (swipe up), unless the
     *  card would no longer fit it - then it says so rather than overflowing. */
    /** @return false at the start/end of the song, when there is no line to bring in. */
    private boolean expandSelection(boolean down) {
        if (document == null) return false;
        int newStart = startIndex;
        int newEnd = endIndex;
        if (down) {
            while (newEnd < document.appliedLines.size() - 1) {
                newEnd++;
                if (!isBlankLine(newEnd)) break;
            }
        } else {
            while (newStart > 0) {
                newStart--;
                if (!isBlankLine(newStart)) break;
            }
        }
        if (newStart == startIndex && newEnd == endIndex) return false;
        // Full card: the new line still comes in, and the line at the far end is pushed out -
        // the selection scrolls through the song instead of stopping.
        boolean pushed = false;
        while (!selectionFits(newStart, newEnd)) {
            if (down) {
                int next = nextLyric(newStart, newEnd);
                if (next < 0) break;
                newStart = next;
            } else {
                int prev = previousLyric(newEnd, newStart);
                if (prev < 0) break;
                newEnd = prev;
            }
            pushed = true;
        }
        startIndex = newStart;
        endIndex = newEnd;
        // Only a line actually leaving the card slides the text; a plain addition settles in place.
        render(!pushed ? Transition.TEXT : down ? Transition.SLIDE_UP : Transition.SLIDE_DOWN);
        return true;
    }

    /**
     * How much lyric a card holds: five lines of ordinary length. Measured as wrapped lines at a
     * reference size across the card's text width, so short lines can go a little past five
     * (up to seven when each still fits on one line) and long ones stop sooner.
     */
    static boolean withinLineBudget(List<String> quotes) {
        if (quotes == null || quotes.size() <= 1) return true;
        if (quotes.size() > 7) return false;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(54f);
        int width = W - 240;
        int visualLines = 0;
        for (String quote : quotes) {
            String text = quote == null ? "" : quote;
            visualLines += Math.max(1, StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .build().getLineCount());
        }
        if (quotes.size() <= 5) return visualLines <= 8;
        return visualLines <= quotes.size();
    }

    private boolean selectionFits(int from, int to) {
        List<String> quotes = collectQuotes(from, to);
        return withinLineBudget(quotes) && fits(quotes, collectTranslations(from, to));
    }

    /** First lyric line after {@code from}, not past {@code limit}; -1 when {@code from} is the last. */
    private int nextLyric(int from, int limit) {
        for (int i = from + 1; i <= limit; i++) if (!isBlankLine(i)) return i;
        return -1;
    }

    /** Last lyric line before {@code from}, not before {@code limit}; -1 when none. */
    private int previousLyric(int from, int limit) {
        for (int i = from - 1; i >= limit; i--) if (!isBlankLine(i)) return i;
        return -1;
    }

    /**
     * Lines added on one design may not fit another (Polaroid's caption is much smaller than
     * Minimal's page), or once the Spotify Code takes its footer. Drops lines from the end until the
     * selection fits the current card again.
     */
    private void trimSelectionToFit() {
        if (document == null || startIndex < 0) return;
        while (endIndex > startIndex && !selectionFits(startIndex, endIndex)) {
            int prev = previousLyric(endIndex, startIndex);
            if (prev < 0) break;
            endIndex = prev;
        }
    }

    private boolean isBlankLine(int index) {
        AppliedLine line = document.appliedLines.get(index);
        return line.dotLine || isBlank(line.text);
    }

    // ---------------------------------------------------------------- rendering + transitions

    private void render(Transition transition) {
        int token = ++generation;
        List<String> quotes = collectQuotes(startIndex, endIndex);
        List<String> translations = collectTranslations(startIndex, endIndex);
        List<Integer> ids = collectQuoteIds(startIndex, endIndex);
        Design d = design;
        Backdrop b = backdrop;
        Bitmap code = spotifyCode ? cachedCode(track, onPaper(d)) : null;
        Bitmap art = artwork;
        Bitmap artist = artistImage;
        Bitmap lyricsBg = lyricsBackground;
        SpotifyTrack t = track;
        RENDER.execute(() -> {
            Bitmap base;
            List<Piece> pieces;
            Bitmap full;
            try {
                base = renderCard(d, b, code, art, artist, lyricsBg, quotes, translations,
                        safe(t.title), safe(t.artist), false);
                pieces = quotePieces(d, quotes, translations, code != null, ids);
                full = base.copy(Bitmap.Config.ARGB_8888, true);
                drawQuoteText(new Canvas(full), d, quotes, translations, code != null);
            } catch (Throwable error) {
                XpLog.log(TAG + " render failed: " + error);
                return;
            }
            main.post(() -> {
                if (token != generation || cardHost == null) return;
                currentBitmap = full;
                boolean textOnly = transition == Transition.SLIDE_UP || transition == Transition.SLIDE_DOWN
                        || transition == Transition.TEXT;
                Runnable apply = () -> {
                    if (token != generation || cardHost == null) return;
                    if (textOnly && currentCard != null) swapText(base, pieces, transition);
                    else swapCard(base, pieces, transition);
                };
                // The pieces are placed in the card's own scale: wait for it to be laid out.
                if (cardHost.getWidth() > 0) apply.run();
                else cardHost.post(apply);
            });
        });
        // The neighbours after the card itself, so the card never waits behind them.
        renderPeeks();
    }

    /**
     * Lines added, pushed out or translated: the card stays put and the lyric lines themselves
     * move. A line that stays glides from where (and how large) it was to its new place - the
     * text re-fits as lines come and go - a new line slides in from the side it was added on, and
     * a line pushed off the card slides out the other way.
     */
    private void swapText(Bitmap base, List<Piece> pieces, Transition transition) {
        currentBase.setImageBitmap(base);
        FrameLayout old = currentTextLayer;
        Map<Integer, ImageView> oldViews = pieceViews;
        Map<Integer, Piece> oldData = pieceData;
        Map<Integer, ImageView> newViews = new java.util.HashMap<>();
        Map<Integer, Piece> newData = new java.util.HashMap<>();
        FrameLayout layer = buildTextLayer(pieces, newViews, newData);
        currentCard.addView(layer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentTextLayer = layer;
        pieceViews = newViews;
        pieceData = newData;

        PathInterpolator ease = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
        float travel = dp(30);
        int oldFirst = Integer.MAX_VALUE;
        int oldLast = Integer.MIN_VALUE;
        for (int id : oldData.keySet()) {
            oldFirst = Math.min(oldFirst, id);
            oldLast = Math.max(oldLast, id);
        }
        int newFirst = Integer.MAX_VALUE;
        for (int id : newData.keySet()) newFirst = Math.min(newFirst, id);
        boolean translationChange = transition == Transition.TEXT;
        float cardScale = cardHost.getWidth() / (float) W;
        for (Piece piece : pieces) {
            ImageView view = newViews.get(piece.id);
            Piece before = oldData.get(piece.id);
            ImageView beforeView = oldViews.get(piece.id);
            if (view == null) continue;
            if (before != null && beforeView != null && piece.id >= 0) {
                float fromY = Math.round(before.y * cardScale) + beforeView.getTranslationY();
                float toY = Math.round(piece.y * cardScale);
                float scale = before.size / Math.max(1f, piece.size);
                view.setTranslationY(fromY - toY);
                view.setScaleX(scale);
                view.setScaleY(scale);
                view.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(460)
                        .setInterpolator(ease).start();
                if (translationChange) {
                    // Its translation appears or leaves: the old rendering follows the move and
                    // dissolves into the new one.
                    view.setAlpha(0f);
                    view.animate().alpha(1f);
                    beforeView.animate().translationY(toY - Math.round(before.y * cardScale))
                            .scaleX(1f / scale).scaleY(1f / scale).alpha(0f)
                            .setDuration(460).setInterpolator(ease).start();
                } else {
                    beforeView.setVisibility(View.INVISIBLE);
                }
            } else {
                boolean below = piece.id > oldLast;
                view.setTranslationY(below ? travel : -travel);
                view.setAlpha(0f);
                view.animate().translationY(0f).alpha(1f).setStartDelay(80).setDuration(460)
                        .setInterpolator(ease).start();
            }
        }
        for (Map.Entry<Integer, ImageView> entry : oldViews.entrySet()) {
            if (newData.containsKey(entry.getKey()) && entry.getKey() >= 0) continue;
            ImageView gone = entry.getValue();
            boolean above = entry.getKey() < newFirst;
            gone.animate().translationYBy(above ? -travel : travel).alpha(0f).setDuration(380)
                    .setInterpolator(ease).start();
        }
        if (old != null) {
            old.postDelayed(() -> {
                if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
            }, 520);
        }
    }

    /** The pieces as views, in the preview's scale of the card. */
    private FrameLayout buildTextLayer(List<Piece> pieces, Map<Integer, ImageView> views,
                                       Map<Integer, Piece> data) {
        FrameLayout layer = new FrameLayout(activity);
        layer.setClipChildren(false);
        float scale = cardHost.getWidth() / (float) W;
        for (Piece piece : pieces) {
            ImageView view = new ImageView(activity);
            view.setImageBitmap(piece.bitmap);
            view.setScaleType(ImageView.ScaleType.FIT_XY);
            // Placed by margins, not setX/setY: those write the translation, which the line
            // animations own (and reset to 0 - every line then landed at the top).
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(1, Math.round(piece.bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(piece.bitmap.getHeight() * scale)));
            lp.leftMargin = Math.round(piece.x * scale);
            lp.topMargin = Math.round(piece.y * scale);
            layer.addView(view, lp);
            view.setPivotX(0f);
            view.setPivotY(0f);
            views.put(piece.id, view);
            data.put(piece.id, piece);
        }
        return layer;
    }

    /**
     * The quote as the card sets it, split per lyric line: same box, size and positions as
     * {@link #drawQuoteText}, each line (with its translation) on its own transparent image.
     */
    private static List<Piece> quotePieces(Design design, List<String> quotes,
                                           List<String> translations, boolean withCode,
                                           List<Integer> ids) {
        List<Piece> out = new ArrayList<>();
        if (quotes == null || quotes.isEmpty()) return out;
        TextBox box = textBox(design, true);
        if (design == Design.GLASS && !withCode) {
            box = box.moved(box.left, box.top, box.height + 130);
        }
        Fitted f = layoutLyrics(quotes, translations, box, false);
        float top = quoteTop(design, box, f.height);
        int width = Math.max(1, (int) Math.ceil(box.width));
        if (f.main.size() != quotes.size()) {
            // Squeezed into one block (lastResort): a single piece.
            Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, (int) Math.ceil(f.height) + 4),
                    Bitmap.Config.ARGB_8888);
            drawFitted(new Canvas(bitmap), f, box.moved(0, 0, box.height), 0f);
            out.add(new Piece(-1, bitmap, box.left, top, f.main.get(0).getPaint().getTextSize()));
            return out;
        }
        float y = top;
        for (int i = 0; i < f.main.size(); i++) {
            StaticLayout main = f.main.get(i);
            StaticLayout sub = f.sub.get(i);
            float size = main.getPaint().getTextSize();
            float height = main.getHeight() + (sub == null ? 0f : size * 0.18f + sub.getHeight());
            Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, (int) Math.ceil(height) + 4),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            main.draw(canvas);
            if (sub != null) {
                canvas.save();
                canvas.translate(0f, main.getHeight() + size * 0.18f);
                sub.draw(canvas);
                canvas.restore();
            }
            int id = ids != null && i < ids.size() ? ids.get(i) : i;
            out.add(new Piece(id, bitmap, box.left, y, size));
            y += height;
            if (i < f.main.size() - 1) y += size * 0.5f;
        }
        return out;
    }

    private void swapCard(Bitmap base, List<Piece> pieces, Transition transition) {
        FrameLayout incoming = new FrameLayout(activity);
        ImageView baseView = new ImageView(activity);
        baseView.setImageBitmap(base);
        baseView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Map<Integer, ImageView> views = new java.util.HashMap<>();
        Map<Integer, Piece> data = new java.util.HashMap<>();
        FrameLayout textLayer = buildTextLayer(pieces, views, data);
        incoming.addView(baseView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        incoming.addView(textLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cardHost.addView(incoming, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout outgoing = currentCard;
        currentCard = incoming;
        currentBase = baseView;
        currentTextLayer = textLayer;
        pieceViews = views;
        pieceData = data;
        PathInterpolator ease = new PathInterpolator(0.2f, 0.9f, 0.2f, 1f);
        float w = cardHost.getWidth() > 0 ? cardHost.getWidth() : dp(300);
        float h = cardHost.getHeight() > 0 ? cardHost.getHeight() : dp(380);
        float fromX = 0f, fromY = 0f, toX = 0f, toY = 0f;
        switch (transition) {
            case SLIDE_LEFT: fromX = w; toX = -w * 0.35f; break;
            case SLIDE_RIGHT: fromX = -w; toX = w * 0.35f; break;
            case SLIDE_UP: fromY = h * 0.18f; toY = -h * 0.12f; break;
            case SLIDE_DOWN: fromY = -h * 0.18f; toY = h * 0.12f; break;
            default: break;
        }
        incoming.setTranslationX(fromX);
        incoming.setTranslationY(fromY);
        incoming.setAlpha(transition == Transition.FADE || fromY != 0f ? 0f : 1f);
        if (outgoing == null) {
            incoming.setScaleX(0.94f);
            incoming.setScaleY(0.94f);
        }
        incoming.animate().translationX(0f).translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(outgoing == null ? 420 : 360).setInterpolator(ease).start();
        if (outgoing != null) {
            outgoing.animate().translationX(toX).translationY(toY)
                    .alpha(0f).scaleX(0.96f).scaleY(0.96f)
                    .setDuration(300).setInterpolator(ease)
                    .withEndAction(() -> {
                        if (cardHost != null) cardHost.removeView(outgoing);
                    }).start();
        }
    }

    /** Document indices of the lines collectQuotes() returns, in the same order. */
    private List<Integer> collectQuoteIds(int from, int to) {
        List<Integer> out = new ArrayList<>();
        if (document == null || from < 0) return out;
        for (int i = from; i <= to && i < document.appliedLines.size(); i++) {
            AppliedLine line = document.appliedLines.get(i);
            if (line.dotLine || isBlank(line.text)) continue;
            out.add(i);
        }
        return out;
    }

    private List<String> collectQuotes(int from, int to) {
        List<String> out = new ArrayList<>();
        if (document == null || from < 0) return out;
        for (int i = from; i <= to && i < document.appliedLines.size(); i++) {
            AppliedLine line = document.appliedLines.get(i);
            if (line.dotLine || isBlank(line.text)) continue;
            out.add(line.text);
        }
        return out;
    }

    private List<String> collectTranslations(int from, int to) {
        List<String> out = new ArrayList<>();
        if (document == null || from < 0) return out;
        for (int i = from; i <= to && i < document.appliedLines.size(); i++) {
            AppliedLine line = document.appliedLines.get(i);
            if (line.dotLine || isBlank(line.text)) continue;
            out.add(showTranslation ? safe(line.translatedText) : "");
        }
        return out;
    }

    private static String webLink(SpotifyTrack track) {
        String link = spotifyUriToWebUrl(track == null ? "" : track.uri);
        return isBlank(link) ? "" : link;
    }

    // ---------------------------------------------------------------- card drawing

    /** Where the lyrics go on a design, and in which colours. */
    private static final class TextBox {
        final float left, top, width, height;
        final int color, subColor;
        final boolean center;
        /** The lyric's typeface; null for the default bold. */
        final Typeface face;

        TextBox(float left, float top, float width, float height, int color, int subColor, boolean center) {
            this(left, top, width, height, color, subColor, center, null);
        }

        TextBox(float left, float top, float width, float height, int color, int subColor, boolean center,
                Typeface face) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.color = color;
            this.subColor = subColor;
            this.center = center;
            this.face = face;
        }

        TextBox moved(float left, float top, float height) {
            return new TextBox(left, top, width, height, color, subColor, center, face);
        }
    }

    /** Where the lyric block starts in its box: centred, bottom-anchored or a little above centre. */
    private static float quoteTop(Design design, TextBox box, float textHeight) {
        float free = Math.max(0f, box.height - textHeight);
        switch (design) {
            case MINIMAL:
            case SPOTLIGHT:
                return box.top + free / 2f;
            case POSTER:
                return box.top + free;
            default:
                return box.top + free * 0.45f;
        }
    }

    /** Designs printed on paper take the Spotify Code with dark bars. */
    private static boolean onPaper(Design design) {
        return design == Design.POLAROID || design == Design.TICKET;
    }

    private boolean fits(List<String> quotes, List<String> translations) {
        TextBox box = textBox(design, !quotes.isEmpty());
        return layoutLyrics(quotes, translations, box, true) != null;
    }

    private static TextBox textBox(Design design, boolean hasQuotes) {
        switch (design) {
            case POLAROID:
                return new TextBox(150, 800, W - 300, 330, Color.rgb(28, 28, 30), Color.rgb(110, 110, 116), false);
            case MINIMAL:
                return new TextBox(90, 110, W - 180, 920, Color.WHITE, Color.argb(170, 255, 255, 255), false);
            case CLASSIC:
                return new TextBox(90, 250, W - 180, 740, Color.WHITE, Color.argb(180, 255, 255, 255), false);
            case POSTER:
                return new TextBox(90, 540, W - 180, 600, Color.WHITE, Color.argb(190, 255, 255, 255), false);
            case VINYL:
                return new TextBox(110, 640, W - 220, 470, Color.WHITE, Color.argb(180, 255, 255, 255), true);
            case TICKET:
                return new TextBox(170, 280, W - 340, 670, Color.rgb(30, 30, 34), Color.rgb(112, 108, 100),
                        false, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            case SPOTLIGHT:
                return new TextBox(100, 530, W - 200, 580, Color.WHITE, Color.argb(180, 255, 255, 255), true);
            default:
                return new TextBox(150, 380, W - 300, 610, Color.WHITE, Color.argb(185, 255, 255, 255), false);
        }
    }

    private static final class Fitted {
        final List<StaticLayout> main = new ArrayList<>();
        final List<StaticLayout> sub = new ArrayList<>();
        float height;
    }

    /** Largest size (down to the floor) at which the whole selection fits the box, or null. */
    private static Fitted layoutLyrics(List<String> quotes, List<String> translations, TextBox box,
                                       boolean strict) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(box.color);
        paint.setTypeface(box.face != null ? box.face : Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        sub.setColor(box.subColor);
        if (box.face != null) sub.setTypeface(Typeface.create(box.face, Typeface.NORMAL));
        for (float size = MAX_TEXT; size >= MIN_TEXT; size -= 2f) {
            paint.setTextSize(size);
            sub.setTextSize(Math.max(26f, size * 0.52f));
            Fitted f = new Fitted();
            float gap = size * 0.5f;
            for (int i = 0; i < quotes.size(); i++) {
                StaticLayout l = layout(quotes.get(i), paint, (int) box.width, box.center);
                f.main.add(l);
                f.height += l.getHeight();
                String t = translations != null && i < translations.size() ? translations.get(i) : "";
                if (!isBlank(t)) {
                    StaticLayout sl = layout(t, sub, (int) box.width, box.center);
                    f.sub.add(sl);
                    f.height += size * 0.18f + sl.getHeight();
                } else {
                    f.sub.add(null);
                }
                if (i < quotes.size() - 1) f.height += gap;
            }
            if (f.height <= box.height) return f;
        }
        return strict ? null : lastResort(quotes, paint, box);
    }

    /** A single oversized line: the floor size, ellipsized to the box. Never overflows. */
    private static Fitted lastResort(List<String> quotes, TextPaint paint, TextBox box) {
        paint.setTextSize(MIN_TEXT);
        String joined = TextUtils.join("\n", quotes);
        int maxLines = Math.max(1, (int) (box.height / (MIN_TEXT * 1.2f)));
        StaticLayout l = StaticLayout.Builder.obtain(joined, 0, joined.length(), paint, (int) box.width)
                .setLineSpacing(0f, 1.08f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        Fitted f = new Fitted();
        f.main.add(l);
        f.sub.add(null);
        f.height = l.getHeight();
        return f;
    }

    private static StaticLayout layout(String text, TextPaint paint, int width, boolean center) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.08f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .build();
    }

    private static Bitmap renderCard(Design design, Backdrop backdrop, Bitmap code, Bitmap art,
                                     Bitmap artist, Bitmap lyricsBg, List<String> quotes,
                                     List<String> translations,
                                     String title, String artistName) {
        return renderCard(design, backdrop, code, art, artist, lyricsBg, quotes, translations,
                title, artistName, true);
    }

    /**
     * The lyric text a design sets on the card, drawn on its own so the preview can animate it
     * separately from the card (see swapText). Same boxes and sizes as the full card.
     */
    private static void drawQuoteText(Canvas canvas, Design design, List<String> quotes,
                                      List<String> translations, boolean withCode) {
        if (quotes == null || quotes.isEmpty()) return;
        TextBox box = textBox(design, true);
        // Glass without the code: the lyric also takes the panel's footer.
        if (design == Design.GLASS && !withCode) box = box.moved(box.left, box.top, box.height + 130);
        Fitted f = layoutLyrics(quotes, translations, box, false);
        drawFitted(canvas, f, box, quoteTop(design, box, f.height));
    }

    private static Bitmap renderCard(Design design, Backdrop backdrop, Bitmap code, Bitmap art,
                                     Bitmap artist, Bitmap lyricsBg, List<String> quotes,
                                     List<String> translations,
                                     String title, String artistName, boolean withQuoteText) {
        boolean withCode = code != null;
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, W, H), 64, 64, Path.Direction.CW);
        canvas.clipPath(clip);
        // Poster paints its own full-bleed ground; the rest sit on the chosen backdrop.
        if (design != Design.POSTER) {
            drawBackdrop(canvas, backdrop, art, artist, lyricsBg,
                    design == Design.MINIMAL || design == Design.SPOTLIGHT ? 0.55f : 0.72f);
        }

        boolean hasQuotes = quotes != null && !quotes.isEmpty();
        TextBox box = textBox(design, hasQuotes);
        switch (design) {
            case POLAROID: {
                Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
                shadow.setColor(Color.argb(90, 0, 0, 0));
                shadow.setMaskFilter(new android.graphics.BlurMaskFilter(36, android.graphics.BlurMaskFilter.Blur.NORMAL));
                RectF paper = new RectF(90, 90, W - 90, H - 90);
                canvas.drawRoundRect(new RectF(paper.left, paper.top + 18, paper.right, paper.bottom + 18), 28, 28, shadow);
                Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paperPaint.setColor(Color.rgb(248, 246, 241));
                canvas.drawRoundRect(paper, 28, 28, paperPaint);
                RectF photo = new RectF(150, 150, W - 150, 150 + (W - 300) * 0.82f);
                drawCover(canvas, art, photo, 14);
                if (hasQuotes) {
                    if (withQuoteText) drawQuoteText(canvas, design, quotes, translations, withCode);
                    drawTitleBlock(canvas, title, artistName, 150, H - 160, W - 300 - (withCode ? CODE_W_POLAROID + 30 : 0),
                            Color.rgb(40, 40, 44), Color.rgb(120, 120, 126), 34, 28);
                } else {
                    // No quote: the caption is the song itself, set large in the print's margin.
                    drawTitleBlock(canvas, title, artistName, 150, 965, W - 300,
                            Color.rgb(34, 34, 38), Color.rgb(110, 110, 116), 52, 36);
                }
                if (withCode) drawSpotifyCode(canvas, code, W - 150, H - 160, CODE_W_POLAROID, true);
                break;
            }
            case MINIMAL: {
                if (hasQuotes) {
                    if (withQuoteText) drawQuoteText(canvas, design, quotes, translations, withCode);
                    drawTitleBlock(canvas, title, artistName, 90, H - 125, W - 180 - (withCode ? CODE_W + 40 : 0),
                            Color.WHITE, Color.argb(170, 255, 255, 255), 34, 28);
                } else {
                    // No quote: the cover fills the card, the song name under it.
                    drawCover(canvas, art, new RectF(110, 110, W - 110, 110 + W - 220), 36);
                    drawTitleBlock(canvas, title, artistName, 110, H - 140, W - 220 - (withCode ? CODE_W + 30 : 0),
                            Color.WHITE, Color.argb(170, 255, 255, 255), 44, 32);
                }
                if (withCode) drawSpotifyCode(canvas, code, W - 90, hasQuotes ? H - 125 : H - 140, CODE_W, false);
                break;
            }
            case CLASSIC: {
                Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
                mark.setColor(Color.argb(60, 255, 255, 255));
                mark.setTextSize(300);
                mark.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                Paint line = new Paint();
                line.setColor(Color.argb(50, 255, 255, 255));
                canvas.drawRect(90, H - 290, W - 90, H - 288, line);
                if (hasQuotes) {
                    canvas.drawText("“", 70, 330, mark);
                    if (withQuoteText) drawQuoteText(canvas, design, quotes, translations, withCode);
                    RectF thumb = new RectF(90, H - 250, 90 + 150, H - 100);
                    drawCover(canvas, art, thumb, 20);
                    drawTitleBlock(canvas, title, artistName, 90 + 180, H - 175,
                            W - 90 - 180 - 90 - (withCode ? CODE_W_CLASSIC + 40 : 0),
                            Color.WHITE, Color.argb(180, 255, 255, 255), 40, 32);
                } else {
                    // No quote: a large cover above the rule; no second thumbnail of the same art.
                    float side = H - 290 - 90 - 90;
                    drawCover(canvas, art, new RectF((W - side) / 2f, 90, (W + side) / 2f, 90 + side), 32);
                    drawTitleBlock(canvas, title, artistName, 90, H - 175,
                            W - 180 - (withCode ? CODE_W_CLASSIC + 40 : 0),
                            Color.WHITE, Color.argb(180, 255, 255, 255), 44, 32);
                }
                if (withCode) drawSpotifyCode(canvas, code, W - 90, H - 175, CODE_W_CLASSIC, false);
                break;
            }
            case POSTER: {
                // Poster: the artwork (or the artist photo) full-bleed across the top, melting into
                // a solid colour taken from it; the lyric sits on the join, anchored to the title.
                Bitmap hero = backdrop == Backdrop.ARTIST && artist != null ? artist : art;
                int ground = extractGradient(art != null ? art : artist)[1];
                Paint fill = new Paint();
                fill.setColor(ground);
                canvas.drawRect(0, 0, W, H, fill);
                if (hero != null) {
                    canvas.drawBitmap(hero, centreCrop(hero, 1f), new RectF(0, 0, W, W),
                            new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG));
                }
                Paint melt = new Paint();
                melt.setShader(new LinearGradient(0, 0, 0, W,
                        new int[]{Color.argb(80, 0, 0, 0), Color.TRANSPARENT, withAlpha(ground, 160),
                                ground, ground},
                        new float[]{0f, 0.28f, 0.55f, 0.88f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, W, W + 2, melt);
                float rowY = hasQuotes ? H - 120 : H - 190;
                if (hasQuotes) {
                    if (withQuoteText) drawQuoteText(canvas, design, quotes, translations, withCode);
                    drawTitleBlock(canvas, title, artistName, 90, rowY, W - 180 - (withCode ? CODE_W + 40 : 0),
                            Color.WHITE, Color.argb(180, 255, 255, 255), 36, 29);
                } else {
                    drawTitleBlock(canvas, title, artistName, 90, rowY, W - 180 - (withCode ? CODE_W + 40 : 0),
                            Color.WHITE, Color.argb(180, 255, 255, 255), 62, 38);
                }
                if (withCode) drawSpotifyCode(canvas, code, W - 90, rowY, CODE_W, false);
                break;
            }
            case VINYL: {
                // Vinyl: a record with the artwork as its label, the lyric set centred beneath it.
                float cx = W / 2f;
                drawRecord(canvas, art, cx, hasQuotes ? 330 : 560, hasQuotes ? 250 : 400);
                if (hasQuotes && withQuoteText) drawQuoteText(canvas, design, quotes, translations, withCode);
                float rowY = hasQuotes ? H - 130 : H - 170;
                float titleSize = hasQuotes ? 36 : 50;
                float artistSize = hasQuotes ? 29 : 34;
                if (withCode) {
                    drawTitleBlock(canvas, title, artistName, 110, rowY, W - 220 - CODE_W_CLASSIC - 40,
                            Color.WHITE, Color.argb(180, 255, 255, 255), titleSize, artistSize);
                    drawSpotifyCode(canvas, code, W - 110, rowY, CODE_W_CLASSIC, false);
                } else {
                    drawTitleBlock(canvas, title, artistName, 110, rowY, W - 220,
                            Color.WHITE, Color.argb(180, 255, 255, 255), titleSize, artistSize, true);
                }
                break;
            }
            case TICKET: {
                // Ticket: a paper stub with notches at the perforation, the lyric typed on it.
                RectF paper = new RectF(110, 110, W - 110, H - 110);
                float perforation = H - 340;
                Path shape = new Path();
                shape.addRoundRect(paper, 24, 24, Path.Direction.CW);
                Path notches = new Path();
                notches.addCircle(paper.left, perforation, 34, Path.Direction.CW);
                notches.addCircle(paper.right, perforation, 34, Path.Direction.CW);
                shape.op(notches, Path.Op.DIFFERENCE);
                Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
                shadow.setColor(Color.argb(90, 0, 0, 0));
                shadow.setMaskFilter(new android.graphics.BlurMaskFilter(36, android.graphics.BlurMaskFilter.Blur.NORMAL));
                canvas.save();
                canvas.translate(0, 16);
                canvas.drawPath(shape, shadow);
                canvas.restore();
                Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paperPaint.setColor(Color.rgb(250, 247, 240));
                canvas.drawPath(shape, paperPaint);
                Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
                dash.setStyle(Paint.Style.STROKE);
                dash.setStrokeWidth(3);
                dash.setColor(Color.argb(90, 60, 56, 50));
                dash.setPathEffect(new android.graphics.DashPathEffect(new float[]{14, 12}, 0));
                canvas.drawLine(paper.left + 50, perforation, paper.right - 50, perforation, dash);
                dash.setStrokeWidth(2);
                canvas.drawLine(170, 236, W - 170, 236, dash);
                TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                head.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                head.setTextSize(30);
                head.setLetterSpacing(0.16f);
                head.setColor(Color.rgb(70, 66, 60));
                canvas.drawText("♪ ADMIT ONE", 170, 200, head);
                if (hasQuotes) {
                    if (withQuoteText) drawQuoteText(canvas, design, quotes, translations, withCode);
                } else {
                    drawCover(canvas, art, new RectF((W - 600) / 2f, 290, (W + 600) / 2f, 890), 12);
                }
                float stubY = (perforation + paper.bottom) / 2f;
                int ink = Color.rgb(30, 30, 34);
                int faded = Color.rgb(112, 108, 100);
                if (withCode) {
                    drawTitleBlock(canvas, title, artistName, 170, stubY, W - 340 - 360 - 30,
                            ink, faded, 36, 28);
                    drawSpotifyCode(canvas, code, W - 170, stubY, 360, true);
                } else {
                    drawCover(canvas, art, new RectF(170, stubY - 75, 320, stubY + 75), 10);
                    drawTitleBlock(canvas, title, artistName, 350, stubY, W - 170 - 350,
                            ink, faded, 38, 29);
                }
                break;
            }
            case SPOTLIGHT: {
                // Spotlight: everything centred under a round cover, like a stage.
                float cx = W / 2f;
                if (hasQuotes) {
                    drawRoundCover(canvas, art, cx, 230, 105);
                    drawTitleBlock(canvas, title, artistName, 120, 420, W - 240,
                            Color.WHITE, Color.argb(180, 255, 255, 255), 34, 27, true);
                    Paint rule = new Paint();
                    rule.setColor(Color.argb(90, 255, 255, 255));
                    canvas.drawRect(cx - 36, 490, cx + 36, 493, rule);
                    if (withQuoteText) drawQuoteText(canvas, design, quotes, translations, withCode);
                } else {
                    drawRoundCover(canvas, art, cx, 540, 330);
                    drawTitleBlock(canvas, title, artistName, 100, 1000, W - 200,
                            Color.WHITE, Color.argb(180, 255, 255, 255), 54, 36, true);
                }
                if (withCode) drawSpotifyCode(canvas, code, cx + 200, H - 120, 400, false);
                break;
            }
            default: {
                // Glass: a frosted panel over the backdrop.
                RectF panel = new RectF(90, 150, W - 90, H - 150);
                Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
                glass.setColor(Color.argb(40, 255, 255, 255));
                canvas.drawRoundRect(panel, 48, 48, glass);
                Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
                edge.setStyle(Paint.Style.STROKE);
                edge.setStrokeWidth(2.5f);
                edge.setColor(Color.argb(70, 255, 255, 255));
                canvas.drawRoundRect(panel, 48, 48, edge);
                if (hasQuotes) {
                    RectF thumb = new RectF(150, 210, 150 + 120, 330);
                    drawCover(canvas, art, thumb, 18);
                    drawTitleBlock(canvas, title, artistName, 150 + 150, 270, W - 150 - 150 - 150,
                            Color.WHITE, Color.argb(185, 255, 255, 255), 36, 29);
                    if (withQuoteText) drawQuoteText(canvas, design, quotes, translations, withCode);
                } else {
                    // No quote: the cover fills the panel, the song name beneath it.
                    drawCover(canvas, art, new RectF(150, 210, W - 150, 210 + W - 300), 28);
                    drawTitleBlock(canvas, title, artistName, 150, H - 210, W - 300 - (withCode ? CODE_W_POLAROID + 30 : 0),
                            Color.WHITE, Color.argb(185, 255, 255, 255), 44, 32);
                }
                if (withCode) drawSpotifyCode(canvas, code, W - 150, H - 210, CODE_W_POLAROID, false);
                break;
            }
        }
        drawBrandMark(canvas, design);
        return bitmap;
    }

    /**
     * A small "SpicyEx" in the top-right corner, kept clear of everything the designs put at the
     * bottom (title, Spotify Code): in the paper's margin for Polaroid, above the frosted panel for
     * Glass, in the free corner for Minimal and Classic. Sized from the card, not the screen.
     */
    private static void drawBrandMark(Canvas canvas, Design design) {
        TextPaint mark = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setTextSize(W * 0.024f);
        mark.setLetterSpacing(0.04f);
        mark.setTextAlign(Paint.Align.RIGHT);
        float right;
        float baseline;
        switch (design) {
            case POLAROID:
                mark.setColor(Color.argb(120, 40, 40, 44));
                right = W - 150;
                baseline = 128;
                break;
            case GLASS:
                mark.setColor(Color.argb(130, 255, 255, 255));
                right = W - 100;
                baseline = 118;
                break;
            case CLASSIC:
                mark.setColor(Color.argb(130, 255, 255, 255));
                right = W - 90;
                baseline = 110;
                break;
            case TICKET:
                // Across from the ticket's header, in its ink.
                mark.setColor(Color.argb(130, 60, 56, 50));
                right = W - 170;
                baseline = 200;
                break;
            case POSTER:
                // Over the artwork: a soft shadow keeps it readable on a light cover.
                mark.setColor(Color.argb(190, 255, 255, 255));
                mark.setShadowLayer(8, 0, 2, Color.argb(120, 0, 0, 0));
                right = W - 90;
                baseline = 82;
                break;
            default:
                mark.setColor(Color.argb(130, 255, 255, 255));
                right = W - 90;
                baseline = 82;
                break;
        }
        canvas.drawText("SpicyEx", right, baseline, mark);
    }

    private static void drawFitted(Canvas canvas, Fitted f, TextBox box, float top) {
        float y = top;
        for (int i = 0; i < f.main.size(); i++) {
            StaticLayout l = f.main.get(i);
            canvas.save();
            canvas.translate(box.left, y);
            l.draw(canvas);
            canvas.restore();
            y += l.getHeight();
            StaticLayout sl = f.sub.get(i);
            if (sl != null) {
                float size = l.getPaint().getTextSize();
                y += size * 0.18f;
                canvas.save();
                canvas.translate(box.left, y);
                sl.draw(canvas);
                canvas.restore();
                y += sl.getHeight();
            }
            if (i < f.main.size() - 1) y += l.getPaint().getTextSize() * 0.5f;
        }
    }

    /**
     * Title (up to two lines, then ellipsized) over the artist (one line), the whole block
     * vertically centred on {@code centreY} so a wrapped title grows both ways around the anchor.
     */
    private static void drawTitleBlock(Canvas canvas, String title, String artist, float left,
                                       float centreY, float maxWidth, int color, int subColor,
                                       float titleSize, float artistSize) {
        drawTitleBlock(canvas, title, artist, left, centreY, maxWidth, color, subColor, titleSize,
                artistSize, false);
    }

    /** As above; {@code center} centres both lines within {@code maxWidth} from {@code left}. */
    private static void drawTitleBlock(Canvas canvas, String title, String artist, float left,
                                       float centreY, float maxWidth, int color, int subColor,
                                       float titleSize, float artistSize, boolean center) {
        Layout.Alignment align = center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        int width = Math.max(10, Math.round(maxWidth));
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(titleSize);
        TextPaint a = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        a.setColor(subColor);
        a.setTextSize(artistSize);
        String ti = title == null ? "" : title;
        StaticLayout titleLayout = StaticLayout.Builder.obtain(ti, 0, ti.length(), t, width)
                .setAlignment(align)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.04f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .build();
        String ar = artist == null ? "" : artist;
        StaticLayout artistLayout = StaticLayout.Builder.obtain(ar, 0, ar.length(), a, width)
                .setAlignment(align)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        float gap = artistSize * 0.25f;
        float height = titleLayout.getHeight() + gap + artistLayout.getHeight();
        canvas.save();
        canvas.translate(left, centreY - height / 2f);
        titleLayout.draw(canvas);
        canvas.translate(0, titleLayout.getHeight() + gap);
        artistLayout.draw(canvas);
        canvas.restore();
    }

    /**
     * The track's Spotify Code. Fetched as white bars on black and screen-blended, so on a dark
     * card the black drops out and only the bars remain; on paper, black bars on white multiplied
     * in for the same effect the other way round.
     */
    private static void drawSpotifyCode(Canvas canvas, Bitmap code, float right, float centreY,
                                        float width, boolean onPaper) {
        if (code == null || code.getWidth() <= 0) return;
        float height = width * code.getHeight() / code.getWidth();
        float left = right - width;
        float top = centreY - height / 2f;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (Build.VERSION.SDK_INT >= 29) {
            paint.setBlendMode(onPaper ? android.graphics.BlendMode.MULTIPLY
                    : android.graphics.BlendMode.SCREEN);
        }
        if (!onPaper) paint.setAlpha(230);
        canvas.drawBitmap(code, null, new RectF(left, top, left + width, top + height), paint);
    }

    private static void drawCover(Canvas canvas, Bitmap art, RectF frame, float radius) {
        if (art == null) return;
        Path clip = new Path();
        clip.addRoundRect(frame, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawBitmap(art, centreCrop(art, frame.width() / frame.height()), frame,
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        canvas.restore();
    }

    /** The artwork in a circle, lifted off the card by a soft shadow and a thin light ring. */
    private static void drawRoundCover(Canvas canvas, Bitmap art, float cx, float cy, float r) {
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(110, 0, 0, 0));
        shadow.setMaskFilter(new android.graphics.BlurMaskFilter(r * 0.18f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        canvas.drawCircle(cx, cy + r * 0.06f, r, shadow);
        if (art != null) {
            Path clip = new Path();
            clip.addCircle(cx, cy, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(art, centreCrop(art, 1f), new RectF(cx - r, cy - r, cx + r, cy + r),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            canvas.restore();
        } else {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.argb(60, 255, 255, 255));
            canvas.drawCircle(cx, cy, r, fill);
        }
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(3f, r * 0.02f));
        ring.setColor(Color.argb(90, 255, 255, 255));
        canvas.drawCircle(cx, cy, r + ring.getStrokeWidth() * 2.5f, ring);
    }

    /** A black record with fine grooves and a light sheen, the artwork as its centre label. */
    private static void drawRecord(Canvas canvas, Bitmap art, float cx, float cy, float r) {
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(130, 0, 0, 0));
        shadow.setMaskFilter(new android.graphics.BlurMaskFilter(r * 0.12f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        canvas.drawCircle(cx, cy + r * 0.05f, r, shadow);
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(Color.rgb(16, 16, 18));
        canvas.drawCircle(cx, cy, r, disc);
        Paint groove = new Paint(Paint.ANTI_ALIAS_FLAG);
        groove.setStyle(Paint.Style.STROKE);
        groove.setStrokeWidth(1.6f);
        groove.setColor(Color.argb(24, 255, 255, 255));
        for (float g = r * 0.44f; g < r * 0.97f; g += r * 0.034f) canvas.drawCircle(cx, cy, g, groove);
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        int light = Color.argb(46, 255, 255, 255);
        sheen.setShader(new android.graphics.SweepGradient(cx, cy,
                new int[]{Color.TRANSPARENT, light, Color.TRANSPARENT, Color.TRANSPARENT, light,
                        Color.TRANSPARENT, Color.TRANSPARENT},
                new float[]{0.02f, 0.1f, 0.2f, 0.52f, 0.6f, 0.7f, 1f}));
        canvas.drawCircle(cx, cy, r, sheen);
        float label = r * 0.38f;
        if (art != null) {
            Path clip = new Path();
            clip.addCircle(cx, cy, label, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(art, centreCrop(art, 1f), new RectF(cx - label, cy - label, cx + label, cy + label),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            canvas.restore();
        } else {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.rgb(200, 64, 60));
            canvas.drawCircle(cx, cy, label, fill);
        }
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(4);
        rim.setColor(Color.argb(70, 0, 0, 0));
        canvas.drawCircle(cx, cy, label, rim);
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setColor(Color.rgb(12, 12, 14));
        canvas.drawCircle(cx, cy, Math.max(6f, r * 0.03f), hole);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static android.graphics.Rect centreCrop(Bitmap bitmap, float aspect) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w / (float) h > aspect) {
            int cw = Math.round(h * aspect);
            return new android.graphics.Rect((w - cw) / 2, 0, (w + cw) / 2, h);
        }
        int ch = Math.round(w / aspect);
        return new android.graphics.Rect(0, (h - ch) / 2, w, (h + ch) / 2);
    }

    private static void drawBackdrop(Canvas canvas, Backdrop backdrop, Bitmap art, Bitmap artist,
                                     Bitmap lyricsBg, float dim) {
        RectF full = new RectF(0, 0, W, H);
        if (backdrop == Backdrop.COLOR && lyricsBg != null) {
            // The lyrics screen's own animated background, as it looked when the sheet opened;
            // it is already dimmed the way the user set it, so only a light vignette goes on top.
            canvas.drawBitmap(lyricsBg, null, full, new Paint(Paint.FILTER_BITMAP_FLAG));
            Paint vignette = new Paint();
            vignette.setShader(new RadialGradient(W / 2f, H / 2f, H * 0.75f,
                    Color.TRANSPARENT, Color.argb(90, 0, 0, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(full, vignette);
            return;
        }
        if (backdrop == Backdrop.COLOR || art == null && artist == null) {
            int[] gradient = extractGradient(art);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setShader(new LinearGradient(0, 0, W, H, gradient[0], gradient[1], Shader.TileMode.CLAMP));
            canvas.drawRect(full, bg);
        } else if (backdrop == Backdrop.ARTIST && artist != null) {
            // The photo itself, sharp (it used to be shrunk and re-enlarged, which read as a
            // blown-up thumbnail); darkened toward the bottom so the text stays legible.
            canvas.drawBitmap(artist, centreCrop(artist, W / (float) H), full,
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG));
            Paint shade = new Paint();
            shade.setShader(new LinearGradient(0, 0, 0, H,
                    Color.argb(90, 0, 0, 0), Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(full, shade);
            return;
        } else {
            // The lyrics screen's look: artwork dissolved into soft colour fields.
            Bitmap blurred = softBlur(art != null ? art : artist, 14);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            ColorMatrix saturate = new ColorMatrix();
            saturate.setSaturation(1.5f);
            p.setColorFilter(new ColorMatrixColorFilter(saturate));
            canvas.drawBitmap(blurred, centreCrop(blurred, W / (float) H), full, p);
        }
        Paint darken = new Paint();
        darken.setColor(Color.argb(Math.round(255 * (1f - dim) * 0.9f + 40), 0, 0, 0));
        canvas.drawRect(full, darken);
        Paint vignette = new Paint();
        vignette.setShader(new RadialGradient(W / 2f, H / 2f, H * 0.75f,
                Color.TRANSPARENT, Color.argb(120, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawRect(full, vignette);
    }

    /** Cheap, smooth blur: shrink to {@code tiny} px then scale back up twice (bilinear). */
    private static Bitmap softBlur(Bitmap source, int tiny) {
        int w = source.getWidth();
        int h = source.getHeight();
        float aspect = w / (float) h;
        int sw = aspect >= 1f ? tiny : Math.max(2, Math.round(tiny * aspect));
        int sh = aspect >= 1f ? Math.max(2, Math.round(tiny / aspect)) : tiny;
        Bitmap small = Bitmap.createScaledBitmap(source, sw, sh, true);
        Bitmap mid = Bitmap.createScaledBitmap(small, sw * 4, sh * 4, true);
        return Bitmap.createScaledBitmap(mid, Math.min(1080, sw * 16), Math.min(1350, sh * 16), true);
    }

    private static int[] extractGradient(Bitmap art) {
        if (art == null || art.getWidth() <= 0 || art.getHeight() <= 0) {
            return new int[]{Color.rgb(60, 60, 66), Color.rgb(14, 14, 16)};
        }
        Bitmap small = Bitmap.createScaledBitmap(art, 20, 20, true);
        long r = 0, g = 0, b = 0;
        int[] pixels = new int[400];
        small.getPixels(pixels, 0, 20, 0, 0, 20, 20);
        for (int pixel : pixels) {
            r += Color.red(pixel);
            g += Color.green(pixel);
            b += Color.blue(pixel);
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / 400), (int) (g / 400), (int) (b / 400), hsv);
        float[] top = hsv.clone();
        top[1] = Math.min(1f, top[1] * 1.3f + 0.1f);
        top[2] = Math.max(top[2], 0.5f);
        float[] bottom = hsv.clone();
        bottom[2] = Math.min(bottom[2] * 0.28f, 0.16f);
        return new int[]{Color.HSVToColor(top), Color.HSVToColor(bottom)};
    }

    // ---------------------------------------------------------------- Spotify Code

    private static Bitmap cachedCode(SpotifyTrack track, boolean paper) {
        synchronized (SPOTIFY_CODES) {
            return SPOTIFY_CODES.get(safe(track == null ? "" : track.uri) + (paper ? "|paper" : "|dark"));
        }
    }

    /** Both variants from Spotify's own scannables endpoint, then re-render. */
    private void fetchSpotifyCode() {
        SpotifyTrack t = track;
        String uri = safe(t == null ? "" : t.uri);
        if (uri.isEmpty() || (cachedCode(t, false) != null && cachedCode(t, true) != null)) return;
        RENDER.execute(() -> {
            boolean ok = true;
            for (boolean paper : new boolean[]{false, true}) {
                if (cachedCode(t, paper) != null) continue;
                String url = "https://scannables.scdn.co/uri/plain/png/"
                        + (paper ? "FFFFFF/black" : "000000/white") + "/640/" + uri;
                try (Response response = NativeRuntime.HTTP.newCall(
                        new Request.Builder().url(url).get().build()).execute()) {
                    Bitmap image = response.isSuccessful() && response.body() != null
                            ? BitmapFactory.decodeStream(response.body().byteStream()) : null;
                    if (image == null) {
                        ok = false;
                        continue;
                    }
                    synchronized (SPOTIFY_CODES) {
                        SPOTIFY_CODES.put(uri + (paper ? "|paper" : "|dark"), image);
                        while (SPOTIFY_CODES.size() > 8) {
                            SPOTIFY_CODES.remove(SPOTIFY_CODES.keySet().iterator().next());
                        }
                    }
                } catch (Throwable error) {
                    ok = false;
                    XpLog.log(TAG + " spotify code failed: " + error.getClass().getSimpleName());
                }
            }
            boolean fetched = ok;
            main.post(() -> {
                if (track != t || overlay == null) return;
                if (!fetched) {
                    Toast.makeText(activity, s("code_unavailable", "Spotify Code unavailable"),
                            Toast.LENGTH_SHORT).show();
                }
                if (spotifyCode) render(Transition.FADE);
            });
        });
    }

    // ---------------------------------------------------------------- artist photo

    private static Bitmap cachedArtist(SpotifyTrack track) {
        synchronized (ARTIST_IMAGES) {
            return ARTIST_IMAGES.get(trackIdFromUri(track == null ? "" : track.uri));
        }
    }

    /**
     * The track's first artist, then their photo. The artist comes from Spotify's own track
     * metadata ({@code artist_uri}, read with the player state); the photo from Spotify's public
     * oEmbed endpoint, which needs no token. The Web API route (with Spotify's captured token)
     * stays as a fallback when the metadata had no artist. Every step logs, so a missing photo
     * says why.
     */
    private void fetchArtistImage() {
        SpotifyTrack t = track;
        String trackId = trackIdFromUri(t == null ? "" : t.uri);
        if (trackId.isEmpty()) return;
        String trackUri = safe(t.uri);
        String metaArtist = trackUri.equals(com.eza.spicyex.References.lastTrackUri)
                ? com.eza.spicyex.References.lastArtistUri : "";
        RENDER.execute(() -> {
            Bitmap image = null;
            try {
                String artistId = metaArtist.startsWith("spotify:artist:")
                        ? metaArtist.substring("spotify:artist:".length()) : null;
                if (artistId == null) {
                    XpLog.log(TAG + " artist: no artist_uri in metadata, trying Web API");
                    SpotifyTokenState.Authorized auth = SpotifyTokenStore.authorization(System.currentTimeMillis());
                    if (auth == null) {
                        XpLog.log(TAG + " artist: no Web API token either");
                    } else {
                        org.json.JSONObject trackJson = getJson(
                                "https://api.spotify.com/v1/tracks/" + trackId, auth.token());
                        artistId = trackJson == null ? null
                                : trackJson.getJSONArray("artists").getJSONObject(0).optString("id", null);
                    }
                }
                if (artistId != null) {
                    org.json.JSONObject embed = getJson("https://open.spotify.com/oembed?url="
                            + Uri.encode("https://open.spotify.com/artist/" + artistId), null);
                    String url = embed == null ? null : embed.optString("thumbnail_url", null);
                    // oEmbed hands out a small rendition; ask the CDN for the largest one.
                    if (url != null) image = downloadLargest(url);
                    // Still small (an artist without its own large photo, or an image kind the
                    // size codes don't cover): the Web API lists every size - take the biggest.
                    if (image == null || image.getWidth() < 600) {
                        Bitmap viaApi = artistImageFromWebApi(artistId);
                        if (viaApi != null && (image == null || viaApi.getWidth() > image.getWidth())) {
                            image = viaApi;
                        }
                    }
                    XpLog.log(TAG + " artist: id=" + artistId + " image="
                            + (image == null ? "none" : image.getWidth() + "px"));
                }
            } catch (Throwable error) {
                XpLog.log(TAG + " artist image failed: " + error);
            }
            Bitmap result = image;
            if (result != null) {
                synchronized (ARTIST_IMAGES) {
                    ARTIST_IMAGES.put(trackId, result);
                    while (ARTIST_IMAGES.size() > 4) {
                        ARTIST_IMAGES.remove(ARTIST_IMAGES.keySet().iterator().next());
                    }
                }
            }
            main.post(() -> {
                if (track != t || overlay == null) return;
                if (result == null) {
                    Toast.makeText(activity, s("artist_unavailable", "Artist photo unavailable"),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                artistImage = result;
                if (backdrop == Backdrop.ARTIST) {
                    render(Transition.FADE);
                    renderThumbs();
                }
            });
        });
    }

    /**
     * Spotify CDN image ids start with a size code. oEmbed returns the 320px artist rendition, but
     * some artists come back as the 160px one, or - without a photo of their own - as a 300px or
     * 64px album cover. Each is swapped for the largest code of its kind; the original is kept
     * when the larger one does not exist.
     */
    private static Bitmap downloadLargest(String url) {
        String[][] upgrades = {
                {"ab67616100005174", "ab6761610000e5eb"},
                {"ab6761610000f178", "ab6761610000e5eb"},
                {"ab67616d00001e02", "ab67616d0000b273"},
                {"ab67616d00004851", "ab67616d0000b273"},
        };
        for (String[] pair : upgrades) {
            if (url.contains(pair[0])) {
                Bitmap big = downloadBitmap(url.replace(pair[0], pair[1]));
                if (big != null) return big;
            }
        }
        return downloadBitmap(url);
    }

    private static Bitmap downloadBitmap(String url) {
        try (Response response = NativeRuntime.HTTP.newCall(
                new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return BitmapFactory.decodeStream(response.body().byteStream());
        } catch (Throwable error) {
            return null;
        }
    }

    /** The artist's largest image from the Web API, with Spotify's own captured token. */
    private static Bitmap artistImageFromWebApi(String artistId) {
        try {
            SpotifyTokenState.Authorized auth = SpotifyTokenStore.authorization(System.currentTimeMillis());
            if (auth == null) return null;
            org.json.JSONObject artist = getJson("https://api.spotify.com/v1/artists/" + artistId, auth.token());
            org.json.JSONArray images = artist == null ? null : artist.optJSONArray("images");
            if (images == null || images.length() == 0) return null;
            String best = null;
            int bestWidth = -1;
            for (int i = 0; i < images.length(); i++) {
                org.json.JSONObject img = images.getJSONObject(i);
                int w = img.optInt("width", 0);
                if (w > bestWidth) {
                    bestWidth = w;
                    best = img.optString("url", null);
                }
            }
            return best == null ? null : downloadLargest(best);
        } catch (Throwable error) {
            return null;
        }
    }

    private static org.json.JSONObject getJson(String url, String token) throws Exception {
        Request.Builder builder = new Request.Builder().url(url).get();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try (Response response = NativeRuntime.HTTP.newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                XpLog.log(TAG + " http " + response.code() + " " + url.replaceAll("\\?.*", ""));
                return null;
            }
            return new org.json.JSONObject(response.body().string());
        }
    }

    // ---------------------------------------------------------------- share / save

    private void saveOnly() {
        Bitmap card = currentBitmap;
        if (card == null) return;
        RENDER.execute(() -> {
            Uri uri = saveToGallery(card);
            main.post(() -> Toast.makeText(activity, uri != null ? s("saved", "Saved to Pictures/SpicyEx")
                    : s("save_failed", "Could not save the card"), Toast.LENGTH_SHORT).show());
        });
    }

    private void shareCard() {
        Bitmap card = currentBitmap;
        SpotifyTrack t = track;
        List<String> quotes = collectQuotes(startIndex, endIndex);
        RENDER.execute(() -> {
            try {
                Uri cardUri = card == null ? null : saveToGallery(card);
                String link = webLink(t);
                if (isBlank(link)) link = safe(t.uri);
                String quote = TextUtils.join("\n", quotes);
                String text = isBlank(quote)
                        ? safe(t.title) + " - " + safe(t.artist)
                        : "\"" + quote + "\"\n" + safe(t.title) + " - " + safe(t.artist);
                Intent send = new Intent(Intent.ACTION_SEND);
                if (cardUri != null) {
                    send.setType("image/png");
                    send.putExtra(Intent.EXTRA_STREAM, cardUri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    send.setType("text/plain");
                }
                send.putExtra(Intent.EXTRA_TEXT, text + "\n" + link);
                Intent chooser = Intent.createChooser(send, s("chooser", "Share lyric"));
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                main.post(() -> activity.startActivity(chooser));
            } catch (Throwable error) {
                XpLog.log(TAG + " share failed: " + error);
            }
        });
    }

    // ---------------------------------------------------------------- stories

    /**
     * Story targets, sent the way Spotify's own share sheet sends them (read from its
     * InstagramStories / FacebookStories destinations): the card goes in as a movable sticker
     * over a two-colour background, with the song's link as the story's attribution and, for
     * Instagram, the track as the music sticker's entity. Running inside Spotify, the module
     * signs the request with Spotify's own Facebook App ID - the one those apps check the
     * attribution link against.
     */
    enum StoryTarget {
        INSTAGRAM("com.instagram.android", "com.instagram.share.ADD_TO_STORY"),
        FACEBOOK("com.facebook.katana", "com.facebook.stories.ADD_TO_STORY");

        final String pkg;
        final String action;

        StoryTarget(String pkg, String action) {
            this.pkg = pkg;
            this.action = action;
        }
    }

    private Intent storyIntent(StoryTarget target) {
        Intent intent = new Intent(target.action);
        intent.setPackage(target.pkg);
        intent.setType("image/png");
        return intent;
    }

    private boolean canShareTo(StoryTarget target) {
        try {
            return activity.getPackageManager().resolveActivity(storyIntent(target), 0) != null;
        } catch (Throwable error) {
            return false;
        }
    }

    private void shareToStory(StoryTarget target) {
        Bitmap card = currentBitmap;
        SpotifyTrack t = track;
        Bitmap art = artwork;
        if (card == null || t == null) return;
        RENDER.execute(() -> {
            try {
                Uri sticker = shareableUri(card);
                if (sticker == null) sticker = saveToGallery(card);
                if (sticker == null) throw new IllegalStateException("no uri for the card");
                int[] colors = extractGradient(art);
                Intent intent = storyIntent(target);
                intent.putExtra("interactive_asset_uri", sticker);
                intent.putExtra("top_background_color", hexColor(colors[0]));
                intent.putExtra("bottom_background_color", hexColor(colors[1]));
                String link = webLink(t);
                if (!isBlank(link)) intent.putExtra("content_url", link);
                String appId = facebookAppId();
                if (target == StoryTarget.INSTAGRAM) {
                    intent.putExtra("source_application", appId);
                    String uri = safe(t.uri);
                    if (uri.startsWith("spotify:track:")) {
                        intent.putExtra("com.instagram.sharedSticker.entityURI", uri);
                    }
                } else {
                    intent.putExtra("com.facebook.platform.extra.APPLICATION_ID", appId);
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.grantUriPermission(target.pkg, sticker, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                main.post(() -> {
                    try {
                        activity.startActivity(intent);
                        dismiss();
                    } catch (Throwable error) {
                        XpLog.log(TAG + " story share failed: " + error);
                        Toast.makeText(activity, s("story_failed", "Could not open the app"),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable error) {
                XpLog.log(TAG + " story share failed: " + error);
                main.post(() -> Toast.makeText(activity, s("story_failed", "Could not open the app"),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static String hexColor(int color) {
        return String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    /** Spotify's own Facebook App ID, from its manifest - as its share destinations read it. */
    private String facebookAppId() {
        try {
            android.content.pm.ApplicationInfo info = activity.getPackageManager().getApplicationInfo(
                    activity.getPackageName(), android.content.pm.PackageManager.GET_META_DATA);
            Object id = info.metaData == null ? null : info.metaData.get("com.facebook.sdk.ApplicationId");
            return id == null ? "" : String.valueOf(id);
        } catch (Throwable error) {
            return "";
        }
    }

    /**
     * The card as a file behind Spotify's own share FileProvider ({@code <package>.share}, cache
     * path {@code shareablesdir/}), so a story share leaves nothing in the gallery. Null when the
     * provider is not there (another Spotify build), and the caller falls back to the gallery.
     */
    private Uri shareableUri(Bitmap card) {
        String authority = activity.getPackageName() + ".share";
        if (activity.getPackageManager().resolveContentProvider(authority, 0) == null) return null;
        java.io.File dir = new java.io.File(activity.getCacheDir(), "shareablesdir");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        java.io.File[] old = dir.listFiles((d, name) -> name.startsWith("spicyex_card_"));
        if (old != null) for (java.io.File f : old) f.delete();
        String name = "spicyex_card_" + System.currentTimeMillis() + ".png";
        java.io.File file = new java.io.File(dir, name);
        try (OutputStream out = new java.io.FileOutputStream(file)) {
            card.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable error) {
            XpLog.log(TAG + " shareable write failed: " + error);
            return null;
        }
        // androidx FileProvider's path form: content://<authority>/<path name>/<file>.
        return new Uri.Builder().scheme("content").authority(authority)
                .appendPath("shareables-cache").appendPath(name).build();
    }

    private void copyLink() {
        String link = webLink(track);
        if (isBlank(link)) return;
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spotify", link));
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(activity, s("link_copied", "Link copied"), Toast.LENGTH_SHORT).show();
        }
    }

    private Uri saveToGallery(Bitmap card) {
        Context context = activity.getApplicationContext();
        String name = "spicyex_lyric_" + System.currentTimeMillis() + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpicyEx");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri item = context.getContentResolver().insert(collection, values);
        if (item == null) return null;
        try (OutputStream out = context.getContentResolver().openOutputStream(item)) {
            if (out == null) return null;
            card.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable error) {
            XpLog.log(TAG + " write failed: " + error);
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(item, values, null, null);
        }
        return item;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
