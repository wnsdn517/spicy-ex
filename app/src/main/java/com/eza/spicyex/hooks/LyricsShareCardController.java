package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.xposed.XpLog;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;
import static com.eza.spicyex.lyrics.LyricUtils.spotifyUriToWebUrl;

/**
 * Renders an Apple Music-style share card (quoted lyric line or, with no lyrics available, a
 * plain track card) as a bitmap, shows it in a long-press "pop in" preview over the fullscreen
 * lyrics screen, and hands it to the system share sheet.
 */
final class LyricsShareCardController {
    private static final String TAG = "[SpotifyPlusLyricsShare]";
    private static final int CARD_WIDTH = 1080;
    private static final int CARD_HEIGHT = 1350;
    private static final int MIN_TEXT_SIZE_DP = 22;

    private final Activity activity;
    private View overlay;
    private ImageView cardView;
    private LyricsDocument document;
    private int startIndex, endIndex;
    private boolean showTranslation = true;
    private SpotifyTrack track;
    private Bitmap artwork;
    private ViewGroup root;

    LyricsShareCardController(Activity activity) {
        this.activity = activity;
    }

    /** Long-pressed a real lyric line: quote it, with its translation underneath if present. */
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
        this.root = root;
        this.document = null;
        this.track = track;
        this.artwork = art;
        this.startIndex = -1;
        this.endIndex = -1;
        this.showTranslation = false;
        show();
    }

    private void show() {
        if (activity == null || root == null || track == null) return;
        dismiss();

        Bitmap card = refreshCard();
        if (card == null) return;

        overlay = buildPreview(card);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private Bitmap refreshCard() {
        try {
            List<String> quotes = new ArrayList<>();
            List<String> translations = new ArrayList<>();
            if (document != null && startIndex >= 0) {
                for (int i = startIndex; i <= endIndex; i++) {
                    AppliedLine line = document.appliedLines.get(i);
                    if (line.dotLine || isBlank(line.text)) continue;
                    quotes.add(line.text);
                    translations.add(showTranslation ? line.translatedText : "");
                }
            }
            return renderCard(artwork, quotes, translations, safe(track.title), safe(track.artist));
        } catch (Throwable t) {
            XpLog.log(TAG + " render failed: " + t);
            return null;
        }
    }

    void dismiss() {
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        overlay = null;
    }

    private View buildPreview(Bitmap card) {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.getBackground().setAlpha(0);
        scrim.setOnClickListener(v -> dismiss());

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClickable(true);

        cardView = new ImageView(activity);
        cardView.setImageBitmap(card);
        cardView.setAdjustViewBounds(true);
        int cardWidthPx = Math.round(root.getResources().getDisplayMetrics().widthPixels * 0.78f);
        column.addView(cardView, new LinearLayout.LayoutParams(cardWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT));

        installPreviewGestures(cardView);

        TextView shareButton = pillButton("Share", true);
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shareLp.topMargin = dp(20);
        shareButton.setOnClickListener(v -> {
            shareCard(track);
            dismiss();
        });
        column.addView(shareButton, shareLp);

        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(column, columnLp);

        column.setScaleX(0.55f);
        column.setScaleY(0.55f);
        column.setAlpha(0f);
        scrim.post(() -> {
            fadeScrimIn(scrim);
            column.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(340)
                    .setInterpolator(new OvershootInterpolator(1.05f))
                    .start();
        });
        return scrim;
    }

    private void installPreviewGestures(View view) {
        android.view.GestureDetector detector = new android.view.GestureDetector(activity, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                if (document != null) {
                    showTranslation = !showTranslation;
                    updatePreview();
                }
                return true;
            }

            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float velocityX, float velocityY) {
                if (document == null || startIndex < 0) return false;
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > Math.abs(e2.getX() - e1.getX())) {
                    expandSelection(dy < 0);
                    return true;
                }
                return false;
            }
        });
        view.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return true;
        });
    }

    private void expandSelection(boolean down) {
        if (document == null) return;
        boolean changed = false;
        if (down) {
            if (endIndex < document.appliedLines.size() - 1) {
                endIndex++;
                changed = true;
            }
        } else {
            if (startIndex > 0) {
                startIndex--;
                changed = true;
            }
        }
        if (changed) updatePreview();
    }

    private void updatePreview() {
        Bitmap card = refreshCard();
        if (card != null && cardView != null) {
            // Subtle pop animation to provide feedback that lines were added or mode changed.
            cardView.animate().cancel();
            cardView.animate()
                    .scaleX(0.98f).scaleY(0.98f).alpha(0.85f)
                    .setDuration(80)
                    .withEndAction(() -> {
                        cardView.setImageBitmap(card);
                        cardView.animate()
                                .scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(180)
                                .setInterpolator(new OvershootInterpolator(1.2f))
                                .start();
                    }).start();
        }
    }

    private void fadeScrimIn(FrameLayout scrim) {
        android.animation.ValueAnimator fader = android.animation.ValueAnimator.ofInt(0, 165);
        fader.setDuration(220);
        fader.addUpdateListener(a -> scrim.getBackground().setAlpha((int) a.getAnimatedValue()));
        fader.start();
    }

    private TextView pillButton(String text, boolean primary) {
        TextView button = new TextView(activity);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(28), dp(12), dp(28), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(primary ? Color.rgb(30, 215, 96) : Color.argb(60, 255, 255, 255));
        bg.setCornerRadius(dp(24));
        button.setBackground(bg);
        if (primary) button.setTextColor(Color.BLACK);
        return button;
    }

    private void shareCard(SpotifyTrack track) {
        new Thread(() -> {
            try {
                Bitmap card = refreshCard();
                if (card == null) return;
                Uri cardUri = saveToGallery(card, track);
                String link = spotifyUriToWebUrl(track.uri);
                if (isBlank(link)) link = safe(track.uri);

                StringBuilder quoteBuilder = new StringBuilder();
                if (document != null && startIndex >= 0) {
                    for (int i = startIndex; i <= endIndex; i++) {
                        AppliedLine line = document.appliedLines.get(i);
                        if (line.dotLine || isBlank(line.text)) continue;
                        if (quoteBuilder.length() > 0) quoteBuilder.append("\n");
                        quoteBuilder.append(line.text);
                    }
                }
                String quote = quoteBuilder.toString();
                String text = isBlank(quote)
                        ? safe(track.title) + " - " + safe(track.artist)
                        : "\"" + quote + "\"\n" + safe(track.title) + " - " + safe(track.artist);

                Intent send = new Intent(Intent.ACTION_SEND);
                if (cardUri != null) {
                    send.setType("image/png");
                    send.putExtra(Intent.EXTRA_STREAM, cardUri);
                    send.putExtra(Intent.EXTRA_TEXT, text + "\n" + link);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, text + "\n" + link);
                }
                Intent chooser = Intent.createChooser(send, "Share lyric");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.runOnUiThread(() -> activity.startActivity(chooser));
            } catch (Throwable t) {
                XpLog.log(TAG + " share failed: " + t);
            }
        }).start();
    }

    private Uri saveToGallery(Bitmap card, SpotifyTrack track) {
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
        } catch (Throwable t) {
            XpLog.log(TAG + " write failed: " + t);
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(item, values, null, null);
        }
        return item;
    }

    private Bitmap renderCard(Bitmap art, List<String> quotes, List<String> translations, String title, String artist) {
        Bitmap bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int[] gradient = extractGradient(art);

        Path clip = new Path();
        RectF bounds = new RectF(0, 0, CARD_WIDTH, CARD_HEIGHT);
        clip.addRoundRect(bounds, dp(28), dp(28), Path.Direction.CW);
        canvas.clipPath(clip);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, CARD_WIDTH, CARD_HEIGHT,
                gradient[0], gradient[1], Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, bg);

        boolean hasQuotes = quotes != null && !quotes.isEmpty();
        if (hasQuotes) {
            drawQuoteGlyph(canvas);
            drawQuotesText(canvas, quotes, translations);
        } else if (art != null) {
            drawCenteredArt(canvas, art);
        }

        drawFooter(canvas, art, title, artist, hasQuotes);
        return bitmap;
    }

    private void drawQuoteGlyph(Canvas canvas) {
        Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        mark.setColor(Color.argb(50, 255, 255, 255));
        mark.setTextSize(dp(100));
        mark.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        canvas.drawText("\u201C", dp(48), dp(140), mark);
    }

    private void drawQuotesText(Canvas canvas, List<String> quotes, List<String> translations) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        sub.setColor(Color.argb(180, 255, 255, 255));
        sub.setTypeface(Typeface.DEFAULT);

        int side = dp(48);
        int width = CARD_WIDTH - side * 2;
        int contentTop = dp(132);
        int footerLine = CARD_HEIGHT - dp(210);
        int contentBottom = footerLine - dp(30);
        int available = Math.max(dp(160), contentBottom - contentTop);

        int size = dp(48);
        int subSize = dp(24);
        List<StaticLayout> layouts = new ArrayList<>();
        List<StaticLayout> subLayouts = new ArrayList<>();

        while (true) {
            layouts.clear();
            subLayouts.clear();
            paint.setTextSize(size);
            sub.setTextSize(subSize);
            int totalHeight = 0;
            for (int i = 0; i < quotes.size(); i++) {
                StaticLayout l = staticLayout(quotes.get(i), paint, width);
                layouts.add(l);
                totalHeight += l.getHeight();
                String trans = translations != null && i < translations.size() ? translations.get(i) : "";
                if (!isBlank(trans)) {
                    StaticLayout sl = staticLayout(trans, sub, width);
                    subLayouts.add(sl);
                    totalHeight += dp(8) + sl.getHeight();
                } else {
                    subLayouts.add(null);
                }
                if (i < quotes.size() - 1) totalHeight += dp(24);
            }
            if (totalHeight <= available || (size <= dp(MIN_TEXT_SIZE_DP) && subSize <= dp(16))) break;
            if (size > dp(MIN_TEXT_SIZE_DP)) size -= dp(2);
            else subSize -= dp(1);
        }

        int textHeight = 0;
        for (int i = 0; i < layouts.size(); i++) {
            textHeight += layouts.get(i).getHeight();
            StaticLayout sl = subLayouts.get(i);
            if (sl != null) textHeight += dp(8) + sl.getHeight();
            if (i < layouts.size() - 1) textHeight += dp(24);
        }

        int yOffset = contentTop + Math.max(0, (available - textHeight) / 4);
        yOffset = Math.min(yOffset, Math.max(contentTop, contentBottom - textHeight));

        for (int i = 0; i < layouts.size(); i++) {
            StaticLayout l = layouts.get(i);
            canvas.save();
            canvas.translate(side, yOffset);
            l.draw(canvas);
            canvas.restore();
            yOffset += l.getHeight();
            StaticLayout sl = subLayouts.get(i);
            if (sl != null) {
                yOffset += dp(8);
                canvas.save();
                canvas.translate(side, yOffset);
                sl.draw(canvas);
                canvas.restore();
                yOffset += sl.getHeight();
            }
            if (i < layouts.size() - 1) yOffset += dp(24);
        }
    }

    private void drawCenteredArt(Canvas canvas, Bitmap art) {
        int size = dp(420);
        int left = (CARD_WIDTH - size) / 2;
        int top = dp(280);
        RectF frame = new RectF(left, top, left + size, top + size);
        Path clip = new Path();
        clip.addRoundRect(frame, dp(20), dp(20), Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawBitmap(art, null, frame, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        canvas.restore();
    }

    private void drawFooter(Canvas canvas, Bitmap art, String title, String artist, boolean compact) {
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Color.argb(40, 255, 255, 255));
        canvas.drawLine(dp(48), CARD_HEIGHT - dp(210), CARD_WIDTH - dp(48), CARD_HEIGHT - dp(210), line);
        int footerTop = CARD_HEIGHT - dp(190);
        int thumbSize = dp(96);
        int left = dp(48);
        if (art != null) {
            RectF frame = new RectF(left, footerTop, left + thumbSize, footerTop + thumbSize);
            Path clip = new Path();
            clip.addRoundRect(frame, dp(12), dp(12), Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(art, null, frame, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            canvas.restore();
        }
        int textLeft = art != null ? left + thumbSize + dp(20) : left;
        int maxWidth = CARD_WIDTH - textLeft - dp(48);
        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        titlePaint.setTextSize(dp(34));
        adaptiveTextSize(titlePaint, title, maxWidth, dp(16), dp(34));
        canvas.drawText(title, textLeft, footerTop + dp(42), titlePaint);

        TextPaint artistPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        artistPaint.setColor(Color.argb(190, 255, 255, 255));
        artistPaint.setTextSize(dp(28));
        adaptiveTextSize(artistPaint, artist, maxWidth, dp(12), dp(28));
        canvas.drawText(artist, textLeft, footerTop + dp(84), artistPaint);

        TextPaint mark = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mark.setColor(Color.argb(140, 255, 255, 255));
        mark.setTextSize(dp(24));
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("SpicyEx", left, CARD_HEIGHT - dp(40), mark);
    }

    private static void adaptiveTextSize(TextPaint paint, String text, int maxWidth, float minSize, float maxSize) {
        String safeText = safe(text);
        if (safeText.isEmpty()) return;
        float size = maxSize;
        while (size >= minSize) {
            paint.setTextSize(size);
            float width = paint.measureText(safeText);
            if (width <= maxWidth) return;
            size -= 1f;
        }
        paint.setTextSize(minSize);
    }

    private static StaticLayout staticLayout(String text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.08f)
                .build();
    }

    private static int[] extractGradient(Bitmap art) {
        if (art == null || art.getWidth() <= 0 || art.getHeight() <= 0) {
            return new int[]{Color.rgb(60, 60, 66), Color.rgb(14, 14, 16)};
        }
        Bitmap small = Bitmap.createScaledBitmap(art, 20, 20, true);
        long r = 0, g = 0, b = 0;
        int count = small.getWidth() * small.getHeight();
        int[] pixels = new int[count];
        small.getPixels(pixels, 0, small.getWidth(), 0, 0, small.getWidth(), small.getHeight());
        for (int pixel : pixels) {
            r += Color.red(pixel);
            g += Color.green(pixel);
            b += Color.blue(pixel);
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / count), (int) (g / count), (int) (b / count), hsv);
        float[] top = hsv.clone();
        top[1] = Math.min(1f, top[1] * 1.3f + 0.1f);
        top[2] = Math.max(top[2], 0.5f);
        float[] bottom = hsv.clone();
        bottom[1] = Math.min(1f, bottom[1]);
        bottom[2] = Math.min(bottom[2] * 0.28f, 0.16f);
        return new int[]{Color.HSVToColor(top), Color.HSVToColor(bottom)};
    }

    private int dp(int value) {
        float density = activity == null ? 1f : activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
