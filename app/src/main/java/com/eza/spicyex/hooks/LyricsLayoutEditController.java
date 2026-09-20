package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sideSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.topSystemPadding;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.PanelDialog;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.UiLanguage;
import com.eza.spicyex.settings.SettingsWriter;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Direct-manipulation layout editor: tap/drag the *real* rendered artwork, track text, lyrics
 * text, follow chip, skip chip, and top controls cluster directly on the live lyrics screen to
 * change their {@link Settings} - plus a Background element with no on-screen shape of its own.
 *
 * <p>Added as an overlay directly into {@code shellRoot} - the same {@link NativeSpicyShellViewImpl}
 * the real views already live in, not a separate window - so the selection outline and handles
 * share the exact same coordinate space as the real views with no cross-window alignment math.
 * Every write goes through {@link SettingsWriter}, the same path an ordinary settings row uses, so
 * the real screen underneath updates the moment a value changes.
 */
final class LyricsLayoutEditController {
    private static final int HANDLE_SIZE_DP = 22;
    /** Extra grab margin around the corner grip's drawn bracket. */
    private static final int HANDLE_TOUCH_PAD_DP = 10;
    private static final int TEXT_COLOR = Color.rgb(232, 232, 238);
    private static final int ACCENT_COLOR = Color.rgb(30, 215, 96);
    /** Outline/label color for a capturable element that is not the current selection - the same
     *  muted tone already used throughout this file's option-row labels. */
    private static final int GRAY_COLOR = 0x99FFFFFF;
    /** Outline color actually painted on an UNSELECTED capture. Every capturable element is
     *  outlined at all times so the editor reads as one visual language, but at the full-strength
     *  gray above, seven simultaneous boxes plus a per-lyric-row grid buried the one box the user
     *  was actually editing. Unselected outlines are therefore drawn as a faint hairline: still
     *  legible as "this is tappable", no longer competing with the selection. */
    private static final int GRAY_IDLE_COLOR = 0x3DFFFFFF;
    private static final int OUTLINE_SELECTED_DP = 2;
    private static final int OUTLINE_IDLE_DP = 1;
    /** Options-panel group card: see Session#beginGroup. */
    private static final int GROUP_RADIUS_DP = 14;
    private static final int GROUP_FILL_COLOR = 0x0FFFFFFF;
    private static final int GROUP_TITLE_SP = 12;
    private static final int GROUP_TITLE_COLOR = 0x8AFFFFFF;
    /** Interactive text in the panel. Chips and toggles are what the user aims at, so they read a
     *  step larger than the group titles above them rather than the same size. */
    private static final int CONTROL_TEXT_SP = 15;
    /** Minimum height of a tappable chip/toggle, so every control clears a comfortable target. */
    private static final int CONTROL_MIN_HEIGHT_DP = 44;

    private LyricsLayoutEditController() {
    }

    /** Bundles a floating chip's editor hooks: the real on-screen view to outline, and the pair
     *  of calls that force it visible for the duration of editing (and undo that afterward)
     *  without disturbing whatever made it visible on its own. */
    static final class EditableChip {
        final Supplier<View> view;
        final Runnable showForEditing;
        final Runnable restoreVisibility;

        EditableChip(Supplier<View> view, Runnable showForEditing, Runnable restoreVisibility) {
            this.view = view;
            this.showForEditing = showForEditing;
            this.restoreVisibility = restoreVisibility;
        }
    }

    /** Fluent request builder for {@link #show}. The plain static method grew past a readable
     *  positional-parameter count once the Follow chip, Track text, and Dock elements joined
     *  Artwork/Focus/Text/Background/Skip - this keeps the call site self-describing. */
    static final class Request {
        private Activity activity;
        private ViewGroup shellRoot;
        private Supplier<View> artFrameSupplier;
        private View focusArea;
        private Supplier<ViewGroup> mountedRowsHostSupplier;
        private Supplier<View> trackTextFrameSupplier;
        private Supplier<View> chromeClusterSupplier;
        private boolean landscape;
        private Runnable applyPreferences;
        private Runnable onChromeReveal;
        private Runnable enableDemoData;
        private Runnable disableDemoData;
        private EditableChip skipChip;
        private EditableChip followChip;

        Request activity(Activity value) { this.activity = value; return this; }
        Request shellRoot(ViewGroup value) { this.shellRoot = value; return this; }
        Request artFrameSupplier(Supplier<View> value) { this.artFrameSupplier = value; return this; }
        Request focusArea(View value) { this.focusArea = value; return this; }
        Request mountedRowsHostSupplier(Supplier<ViewGroup> value) { this.mountedRowsHostSupplier = value; return this; }
        Request trackTextFrameSupplier(Supplier<View> value) { this.trackTextFrameSupplier = value; return this; }
        Request chromeClusterSupplier(Supplier<View> value) { this.chromeClusterSupplier = value; return this; }
        Request landscape(boolean value) { this.landscape = value; return this; }
        Request applyPreferences(Runnable value) { this.applyPreferences = value; return this; }
        Request onChromeReveal(Runnable value) { this.onChromeReveal = value; return this; }
        Request enableDemoData(Runnable value) { this.enableDemoData = value; return this; }
        Request disableDemoData(Runnable value) { this.disableDemoData = value; return this; }
        Request skipChip(EditableChip value) { this.skipChip = value; return this; }
        Request followChip(EditableChip value) { this.followChip = value; return this; }

        void show() {
            if (activity == null || shellRoot == null) return;
            new Session(this).start();
        }
    }

    /** One editor invocation's mutable state - a plain instance instead of a pile of one-element
     *  arrays now that there's real state (selected element, snapshot, current drag) to carry. */
    private static final class Session {
        private static final Settings.Setting<?>[] TOUCHED_SETTINGS = {
                Settings.TRACK_INFO_POSITION, Settings.TRACK_INFO_ART_RADIUS,
                Settings.TRACK_INFO_ART_SIZE, Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP,
                Settings.TRACK_INFO_TEXT_ALIGN, Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE,
                Settings.TRACK_INFO_SHOW_TITLE, Settings.TRACK_INFO_SHOW_ARTIST,
                Settings.TRACK_INFO_SHOW_ALBUM,
                Settings.LYRICS_FOCUS_POSITION, Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT,
                Settings.ENABLE_LINE_BLUR, Settings.LYRICS_BLUR_INTENSITY,
                Settings.LYRICS_TEXT_SIZE, Settings.LYRICS_TEXT_SIZE_CUSTOM,
                Settings.LYRICS_FONT, Settings.LYRICS_WEIGHT,
                Settings.LINE_SPACING, Settings.LINE_SPACING_CUSTOM,
                Settings.TRACK_INFO_TEXT_SIZE, Settings.TRACK_INFO_TEXT_SIZE_CUSTOM,
                Settings.BACKGROUND_STYLE, Settings.BEAT_REACTIVE_BACKGROUND,
                Settings.FORCE_DARK_BACKGROUND, Settings.EXTRA_DARK_BACKGROUND,
                Settings.SKIP_CHIP_POSITION, Settings.SKIP_CHIP_STYLE,
                Settings.FOLLOW_CHIP_POSITION, Settings.FOLLOW_CHIP_STYLE,
                Settings.BACKGROUND_RENDER_QUALITY,
                Settings.LIKED_SONGS_BUTTON, Settings.CHROME_CLUSTER_POSITION, Settings.FULLSCREEN_CONTROLS,
                Settings.LYRICS_ADAPTIVE_TEXT_SIZE, Settings.INTERLUDE_ICON,
                Settings.TRACK_INFO_BACKGROUND, Settings.TRACK_INFO_TEXT_OVERFLOW,
                Settings.WORD_BOUNCE, Settings.WORD_BOUNCE_STYLE,
                Settings.ENABLE_GLOW_BLUR, Settings.LINE_SYNC_FILL,
                Settings.ANIMATION_STYLE, Settings.APPLE_CASCADE_SPEED
        };

        /** Which on-screen thing is selected. Artwork and its title/artist text used to be one
         *  bundled element with no outline of its own for the text half - they are now separate so
         *  each can be tapped and configured independently. */
        private enum Element { ARTWORK, TRACK_TEXT, FOCUS, TEXT, BACKGROUND, SKIP, FOLLOW, DOCK }


        private final Activity activity;
        private final ViewGroup shellRoot;
        private final Supplier<View> artFrameSupplier;
        private final View focusArea;
        private final Supplier<ViewGroup> mountedRowsHostSupplier;
        private final Supplier<View> trackTextFrameSupplier;
        private final Supplier<View> chromeClusterSupplier;
        private final SettingsStore store;
        private final SettingsWriter writer;
        private final SettingsUiStrings strings;
        private final Runnable applyPreferences;
        private final Runnable onChromeReveal;
        private final Runnable enableDemoData;
        private final Runnable disableDemoData;
        private final EditableChip skipChip;
        private final EditableChip followChip;

        private final FrameLayout overlay;
        private final FrameLayout artLayer;
        private final FrameLayout trackTextLayer;
        private final FrameLayout skipLayer;
        private final FrameLayout followLayer;
        private final FrameLayout dockLayer;
        private final LinearLayout optionsCard;
        private final MaxHeightScrollView optionsScroll;
        private final LinearLayout panelContainer;

        private Element selected = Element.ARTWORK;
        /** Explicit top margin (px) the options panel currently sits at, once the user has
         *  dragged it - null means "still at its default docked-bottom position", the common
         *  case that needs no persisted number at all. Session-local only, not a setting. */
        private Integer panelTopMargin;
        private Integer panelContainerHeight;
        private boolean panelVisible;
        private View artCapture;
        private View artHandle;
        private View trackTextCapture;
        private View focusHandle;
        private View textOutline;
        private View skipCapture;
        private View followCapture;
        private View dockCapture;
        private boolean demoActive;
        /** Outline -> real view it is tracing. Re-synced every frame by {@link #syncCaptures()}. */
        private final java.util.List<CaptureBinding> captureBindings = new java.util.ArrayList<>();
        private ViewTreeObserver.OnPreDrawListener captureSyncListener;
        /** Set while the corner handle is being dragged: the outline is then driven by the drag
         *  itself and must not be pulled back to the real artwork, which does not resize until the
         *  settings write propagates. */
        private boolean resizingArtwork;

        /** One outline and the real on-screen view it traces. */
        private static final class CaptureBinding {
            final View capture;
            final Supplier<View> source;
            /** Corner grip pinned to the capture's bottom-right, or null. */
            final View handle;
            final int handleInsetPx;

            CaptureBinding(View capture, Supplier<View> source, View handle, int handleInsetPx) {
                this.capture = capture;
                this.source = source;
                this.handle = handle;
                this.handleInsetPx = handleInsetPx;
            }
        }

        private void bindCapture(View capture, Supplier<View> source) {
            bindCapture(capture, source, null, 0);
        }

        private void bindCapture(View capture, Supplier<View> source, View handle, int handleInsetPx) {
            if (capture == null || source == null) return;
            captureBindings.add(new CaptureBinding(capture, source, handle, handleInsetPx));
        }

        /**
         * Re-anchors every outline onto its real view's current on-screen rect.
         *
         * <p>Outlines used to be positioned once, when they were built, from a single
         * {@code relativePosition()} reading. Anything that moved the real view afterwards without
         * going through a rebuild - the ambient background recreating its layer, the chrome
         * auto-hide animating, window insets arriving late, the lyrics column re-laying out after a
         * font or spacing change, a rotation-adjacent resize - left the outline behind at the old
         * rect. The box then no longer sat on the thing it outlined, and tapping the visible
         * element missed it entirely.
         *
         * <p>Running from an {@code OnPreDrawListener} means this reads geometry after layout has
         * settled and before anything is painted, so a corrected outline is never drawn stale for
         * even one frame. Writes are gated on an actual change, so the {@code requestLayout()} a
         * margin change triggers cannot loop: once the outline agrees with its source, nothing is
         * written and the traversal ends.
         */
        private void syncCaptures() {
            if (captureBindings.isEmpty()) return;
            for (int i = 0; i < captureBindings.size(); i++) {
                CaptureBinding binding = captureBindings.get(i);
                View capture = binding.capture;
                if (capture == null || capture.getParent() == null) continue;
                if (resizingArtwork && capture == artCapture) continue;
                View source;
                try {
                    source = binding.source.get();
                } catch (Throwable ignored) {
                    continue;
                }
                if (source == null || !source.isAttachedToWindow()
                        || source.getWidth() <= 0 || source.getHeight() <= 0) {
                    // The real view is gone or not laid out - hide the orphaned outline rather
                    // than leaving it floating at its last known rect.
                    if (capture.getVisibility() != View.GONE) capture.setVisibility(View.GONE);
                    continue;
                }
                if (capture.getVisibility() != View.VISIBLE) capture.setVisibility(View.VISIBLE);
                ViewGroup.LayoutParams raw = capture.getLayoutParams();
                if (!(raw instanceof FrameLayout.LayoutParams)) continue;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                int[] pos = relativePosition(source, shellRoot);
                boolean changed = lp.leftMargin != pos[0] || lp.topMargin != pos[1]
                        || lp.width != source.getWidth() || lp.height != source.getHeight();
                if (changed) {
                    lp.leftMargin = pos[0];
                    lp.topMargin = pos[1];
                    lp.width = source.getWidth();
                    lp.height = source.getHeight();
                    capture.setLayoutParams(lp);
                }
                syncHandle(binding, lp);
            }
        }

        private void syncHandle(CaptureBinding binding, FrameLayout.LayoutParams captureLp) {
            View handle = binding.handle;
            if (handle == null || handle.getParent() == null) return;
            // The grip only means anything while the artwork is the selection; showing it always
            // put a bright green bracket on screen competing with whatever was being edited.
            int wanted = selected == Element.ARTWORK ? View.VISIBLE : View.GONE;
            if (handle.getVisibility() != wanted) handle.setVisibility(wanted);
            if (wanted != View.VISIBLE) return;
            ViewGroup.LayoutParams raw = handle.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            int left = captureLp.leftMargin + captureLp.width - binding.handleInsetPx;
            int top = captureLp.topMargin + captureLp.height - binding.handleInsetPx;
            if (lp.leftMargin == left && lp.topMargin == top) return;
            lp.leftMargin = left;
            lp.topMargin = top;
            handle.setLayoutParams(lp);
        }

        private void startCaptureSync() {
            if (captureSyncListener != null) return;
            captureSyncListener = () -> {
                try {
                    syncCaptures();
                } catch (Throwable ignored) {
                    // Geometry can be read mid-teardown; never take the editor down for it.
                }
                return true;
            };
            overlay.getViewTreeObserver().addOnPreDrawListener(captureSyncListener);
        }

        private void stopCaptureSync() {
            if (captureSyncListener == null) return;
            ViewTreeObserver observer = overlay.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(captureSyncListener);
            captureSyncListener = null;
            captureBindings.clear();
        }

        Session(Request request) {
            this.activity = request.activity;
            this.shellRoot = request.shellRoot;
            this.artFrameSupplier = request.artFrameSupplier;
            this.focusArea = request.focusArea;
            this.mountedRowsHostSupplier = request.mountedRowsHostSupplier;
            this.trackTextFrameSupplier = request.trackTextFrameSupplier;
            this.chromeClusterSupplier = request.chromeClusterSupplier;
            this.applyPreferences = request.applyPreferences;
            this.onChromeReveal = request.onChromeReveal;
            this.enableDemoData = request.enableDemoData;
            this.disableDemoData = request.disableDemoData;
            this.skipChip = request.skipChip;
            this.followChip = request.followChip;
            this.store = new SettingsStore(activity);
            this.writer = new SettingsWriter(store);
            this.strings = UiLanguage.strings(activity, store.get(Settings.UI_LANGUAGE));
            this.overlay = new FrameLayout(activity);
            this.artLayer = new FrameLayout(activity);
            this.trackTextLayer = new FrameLayout(activity);
            this.skipLayer = new FrameLayout(activity);
            this.followLayer = new FrameLayout(activity);
            this.dockLayer = new FrameLayout(activity);
            this.optionsCard = new LinearLayout(activity);
            this.optionsScroll = new MaxHeightScrollView(activity);
            this.panelContainer = new LinearLayout(activity);
            this.panelContainer.setOrientation(LinearLayout.VERTICAL);
        }

        /** Writes through the settings store, then forces the real shell to re-apply - bypassing
         *  the async SharedPreferences-listener round trip, so overlay geometry read afterward
         *  sees the real view already caught up instead of one frame behind. The apply itself is
         *  always posted, never run inline from the touch callback that called put(): some
         *  position changes reparent a whole view subtree (entering/leaving Header mode moves the
         *  art+title row between the chrome header and the track-info box), and doing that
         *  synchronously while this same call stack is still mutating this overlay's own view
         *  tree (removeAllViews()/addView() in refreshArtwork()) can reenter Android's
         *  measure/layout pass mid-traversal and crash with a NPE deep in FrameLayout.onMeasure.
         *  Anything that needs the post-apply real geometry goes in afterApply, chained after. */
        private <T> void put(Settings.Setting<T> setting, T value) {
            put(setting, value, null);
        }

        private <T> void put(Settings.Setting<T> setting, T value, Runnable afterApply) {
            writer.put(setting, value);
            overlay.post(() -> {
                if (applyPreferences != null) applyPreferences.run();
                // applyPreferences() only *requests* a layout pass (requestLayout() never runs
                // synchronously) - reading the frame's geometry immediately after it, as
                // refreshArtwork() does, could still see pre-change values (wrong width/height/
                // position), landing the capture outline somewhere that doesn't overlap the real,
                // now-repositioned artwork - taps on the visible artwork then hit nothing. Wait
                // for an actual layout pass to land before running afterApply.
                if (afterApply != null) afterNextLayout(afterApply);
            });
        }

        /** Runs action after layout on this overlay has settled, or after a short timeout if the
         *  write didn't end up changing any layout (e.g. re-picking the same position) -
         *  onGlobalLayout would otherwise never fire and afterApply would never run at all.
         *
         *  <p>"Settled" means two animation frames pass with no further layout pass in between,
         *  not just the first pass after the write: some position changes (e.g. entering/leaving
         *  Header mode) animate the real view across several frames, and firing on that first,
         *  still-mid-transition pass used to read a stale intermediate rect - the capture outline
         *  then froze there while the real view kept animating to its final spot. */
        private void afterNextLayout(Runnable action) {
            ViewTreeObserver observer = overlay.getViewTreeObserver();
            boolean[] done = {false};
            int[] generation = {0};
            ViewTreeObserver.OnGlobalLayoutListener[] listenerRef =
                    new ViewTreeObserver.OnGlobalLayoutListener[1];
            listenerRef[0] = () -> {
                if (done[0]) return;
                int mine = ++generation[0];
                overlay.postOnAnimation(() -> overlay.postOnAnimation(() -> {
                    if (done[0] || generation[0] != mine) return;
                    done[0] = true;
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listenerRef[0]);
                    action.run();
                }));
            };
            if (observer.isAlive()) observer.addOnGlobalLayoutListener(listenerRef[0]);
            overlay.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listenerRef[0]);
                action.run();
            }, 150L);
        }

        void start() {
            overlay.setBackgroundColor(0x4D000000);
            overlay.setClickable(true);
            // Any tap that reaches the overlay's own background - i.e. not claimed by the
            // artwork, track text, the lyrics layer, the focus handle, the top bar, or the
            // options card - is by definition a tap on open background. No extra geometry
            // bookkeeping needed.
            overlay.setOnClickListener(v -> selectElement(Element.BACKGROUND));

            artLayer.setClipChildren(false);
            overlay.addView(artLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            trackTextLayer.setClipChildren(false);
            overlay.addView(trackTextLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Added before the focus handle so the handle's smaller touch band still wins where
            // the two overlap (later-added children win overlapping touches in a FrameLayout).
            buildTextTapLayer();
            buildFocusHandle();

            // Added after the lyrics tap layer (not into artLayer, which refreshArtwork() clears
            // wholesale on every artwork change) so each chip's capture region wins overlapping
            // touches there instead of being swallowed as a lyrics tap or forwarded as a scroll.
            skipLayer.setClipChildren(false);
            overlay.addView(skipLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            followLayer.setClipChildren(false);
            overlay.addView(followLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            dockLayer.setClipChildren(false);
            overlay.addView(dockLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Auto-preview: opening the editor with nothing actually playing left it showing an
            // empty/error state, so every element had to be selected blind. Full only - the demo's
            // whole point is previewing furigana/pinyin/romanization/translation, none of which
            // exist in the Lite build. Deferred to after the first layout pass so the editor UI
            // appears instantly instead of blocking on document render + bitmap creation.
            if (!demoActive && enableDemoData != null
                    && com.eza.spicyex.FeatureAvailability.transliterationAvailable()) {
                demoActive = true;
                overlay.post(() -> overlay.post(() -> {
                    if (enableDemoData != null) enableDemoData.run();
                }));
            }

            optionsCard.setOrientation(LinearLayout.VERTICAL);
            optionsCard.setPadding(dp(14), dp(10), dp(14), dp(10));

            // The card can run long (Artwork/Background have half a dozen rows each), and in
            // landscape's shorter height a plain WRAP_CONTENT card would grow tall enough to sit
            // under the top bar, with no way to reach whatever scrolled past it. Wrapping it in a
            // scroll view - capped well short of the full height by MaxHeightScrollView - keeps it
            // reachable by drag in both orientations instead.
            // Darker and near-opaque, with a barely-there border and no drop shadow - this panel
            // sits directly over the lyrics it's editing, so it should read as a quiet utility
            // tray rather than a bright card competing with them for attention.
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(0xF20A0A0D);
            cardBg.setCornerRadius(dp(20));
            cardBg.setStroke(dp(1), 0x14FFFFFF);
            optionsScroll.setBackground(cardBg);
            optionsScroll.setElevation(dp(4));
            optionsScroll.setVerticalScrollBarEnabled(false);
            optionsScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            optionsScroll.addView(optionsCard, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            panelContainer.addView(actionIconsRow(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panelContainer.addView(panelDragHandle(), panelDragHandleLp());
            panelContainer.addView(optionsScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            panelContainer.addView(panelBottomResizeHandle(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));

            overlay.addView(panelContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            applyPanelLayout();
            panelContainer.setVisibility(View.INVISIBLE);

            refreshArtwork();
            refreshTrackText();
            selectElement(Element.ARTWORK, false);

            shellRoot.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Floating chips and the top controls cluster only reliably show under their own
            // real-state conditions (an active skip gap, follow state away from the current line,
            // the fullscreen auto-hide timer) - force everything visible for the duration of the
            // edit so there's always something here to select and position.
            if (onChromeReveal != null) onChromeReveal.run();
            if (skipChip != null && skipChip.showForEditing != null) skipChip.showForEditing.run();
            if (followChip != null && followChip.showForEditing != null) followChip.showForEditing.run();
            refreshSkipChip();
            refreshFollowChip();
            refreshDock();
            afterNextLayout(() -> {
                refreshSkipChip();
                refreshFollowChip();
                refreshDock();
                if (panelContainerHeight == null && overlay.getHeight() > 0) {
                    panelContainerHeight = clamp(Math.round(overlay.getHeight() * DEFAULT_PANEL_HEIGHT_FRACTION),
                            dp(200), overlay.getHeight() - dp(PANEL_MIN_TOP_DP));
                    applyPanelLayout();
                }
            });
            startCaptureSync();
        }

        private void close() {
            stopCaptureSync();
            if (demoActive && disableDemoData != null) {
                demoActive = false;
                disableDemoData.run();
            }
            if (skipChip != null && skipChip.restoreVisibility != null) skipChip.restoreVisibility.run();
            if (followChip != null && followChip.restoreVisibility != null) followChip.restoreVisibility.run();
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) parent.removeView(overlay);
            // The overlay's own scrim sat over the real chrome row the whole time it was open;
            // make sure it (and the settings cog on it) is actually visible again afterward
            // rather than relying on whatever auto-hide state it happened to be in already.
            if (onChromeReveal != null) onChromeReveal.run();
        }

        // -- top bar --------------------------------------------------------

        private void onResetClicked() {
            PanelDialog confirm = new PanelDialog(activity,
                    s("reset_confirm_title", "Reset layout?"));
            confirm.paragraph(s("reset_confirm_message",
                    "Reset the layout editor changes to their defaults?"));
            confirm.primary(s("reset", "Reset"), this::resetLayoutToDefaults);
            confirm.secondary(s("cancel", "Cancel"), null);
            confirm.show();
        }

        private void resetLayoutToDefaults() {
            for (Settings.Setting<?> setting : TOUCHED_SETTINGS) {
                restoreTyped(writer, setting, setting.defaultValue);
            }
            overlay.post(() -> {
                if (applyPreferences != null) applyPreferences.run();
                afterNextLayout(() -> {
                    refreshArtwork();
                    refreshTrackText();
                    repaintFocusHandle();
                    refreshSkipChip();
                    refreshFollowChip();
                    refreshDock();
                    selectElement(selected);
                });
            });
        }

        /** Small round icon button (matching the chrome cluster's own icon style). */
        private ImageView iconButton(ActionIconDrawable.Kind kind, int color, String description,
                Runnable onClick) {
            float density = activity.getResources().getDisplayMetrics().density;
            ImageView button = new ImageView(activity);
            button.setImageDrawable(new ActionIconDrawable(kind, color, density));
            button.setScaleType(ImageView.ScaleType.CENTER);
            button.setBackground(NativeIconButtons.createRoundButtonBackground());
            button.setContentDescription(description);
            button.setClickable(true);
            button.setFocusable(true);
            NativeIconButtons.applyPressScale(button);
            button.setOnClickListener(v -> onClick.run());
            return button;
        }

        private LinearLayout.LayoutParams iconButtonLp(int leftMarginDp) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.leftMargin = dp(leftMarginDp);
            return lp;
        }

        private static final Element[] SWITCHABLE_ELEMENTS = {
                Element.ARTWORK, Element.TRACK_TEXT, Element.TEXT, Element.BACKGROUND,
                Element.FOCUS, Element.SKIP, Element.FOLLOW, Element.DOCK
        };

        /** Named chips instead of icons - this is the one place every element (including Artwork
         *  when its own position is "Off" and there's nothing left on screen to tap) is always
         *  reachable by name, scrolling if it doesn't fit. Words read faster than guessing at an
         *  icon's meaning, and there's no separate top bar duplicating this any more. */
        private LinearLayout elementSwitcherRow() {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView selectedChip = null;
            for (Element element : SWITCHABLE_ELEMENTS) {
                TextView chip = chip(labelFor(element));
                chip.setTextSize(11);
                chip.setPadding(dp(3), dp(7), dp(3), dp(7));
                boolean isSelected = selected == element;
                paintChip(chip, isSelected);
                chip.setOnClickListener(v -> selectElement(element));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(4);
                row.addView(chip, lp);
                if (isSelected) selectedChip = chip;
            }
            android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(activity);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            // A plain flat row gave no hint that 8 tabs exist beyond whatever fits on screen -
            // fading edges at least signal "there's more this way" even before the first swipe.
            scroller.setHorizontalFadingEdgeEnabled(true);
            scroller.setFadingEdgeLength(dp(18));
            scroller.addView(row, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout wrapper = new LinearLayout(activity);
            wrapper.addView(scroller, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // Tapping a real on-screen element (not this strip) can select a tab that's currently
            // scrolled out of view, leaving no visible cue of where you are among the 8 - center
            // whichever chip is selected once its width is known.
            TextView toCenter = selectedChip;
            if (toCenter != null) {
                toCenter.post(() -> {
                    int target = toCenter.getLeft() - (scroller.getWidth() - toCenter.getWidth()) / 2;
                    scroller.smoothScrollTo(Math.max(0, target), 0);
                });
            }
            return wrapper;
        }

        /** Editor-level actions: cancel (discard changes) and save (apply & close). All changes
         *  apply live as they're made, so save just closes the editor. */
        private LinearLayout actionIconsRow() {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(12), dp(8), dp(12));

            // Cancel button (left side)
            row.addView(iconButton(ActionIconDrawable.Kind.CLOSE, 0x99FFFFFF,
                    s("cancel", "Cancel"), this::close), iconButtonLp(0));

            // Reset sits beside Cancel so it is discoverable as an editor-level action. It uses
            // refresh geometry because the action restores defaults rather than deleting content.
            row.addView(iconButton(ActionIconDrawable.Kind.REFRESH, 0xB3FFFFFF,
                    s("reset", "Reset"), this::onResetClicked), iconButtonLp(4));

            TextView title = text(s("layout_editor_title", "Layout editor"), 15, TEXT_COLOR, true);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            titleLp.leftMargin = dp(10);
            row.addView(title, titleLp);

            // Spacer to push save button to the right
            LinearLayout spacer = new LinearLayout(activity);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(spacer, spacerLp);

            // Save button (right side)
            row.addView(iconButton(ActionIconDrawable.Kind.CHECK, ACCENT_COLOR,
                    s("save", "Save"), this::close), iconButtonLp(0));
            return row;
        }

        // -- artwork element --------------------------------------------------

        /** Rebuilds the capture outline + resize handle anchored to whichever art frame is
         *  currently visible - called at startup and again after anything that could change
         *  *which* real view is showing (a position change moves the artwork to a different
         *  frame object entirely: top/bottom/side are three separate views). */
        private void refreshArtwork() {
            artLayer.removeAllViews();
            dropBindingsIn(artLayer);
            View frame = artFrameSupplier == null ? null : artFrameSupplier.get();
            if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
                artCapture = null;
                artHandle = null;
                return;
            }
            int[] pos = relativePosition(frame, shellRoot);
            int radiusDp = safeGet(Settings.TRACK_INFO_ART_RADIUS);

            View capture = new View(activity);
            GradientDrawable outline = new GradientDrawable();
            outline.setStroke(dp(2), ACCENT_COLOR);
            outline.setCornerRadius(dp(radiusDp));
            capture.setBackground(outline);
            FrameLayout.LayoutParams captureLp = new FrameLayout.LayoutParams(
                    frame.getWidth(), frame.getHeight(), Gravity.TOP | Gravity.START);
            captureLp.leftMargin = pos[0];
            captureLp.topMargin = pos[1];
            artLayer.addView(capture, captureLp);
            artCapture = capture;
            installArtworkDrag(capture, captureLp);
            paintCapture(artCapture, selected == Element.ARTWORK);

            // An Apple-style curved corner grip - hugging the actual corner point rather than a
            // dot floating centered on top of it - covers a generous touch box for grabbability
            // while only painting the curved bracket itself.
            // Touch box extends a bit past the drawn bracket's vertex for grabbability
            // (HANDLE_SIZE_DP is the bracket's own arm length) - the vertex itself sits at the
            // real frame corner, with both arms drawn extending down-right from it.
            int touchPad = dp(HANDLE_TOUCH_PAD_DP);
            View handle = new CornerGripView(activity, touchPad);
            int handleSize = dp(HANDLE_SIZE_DP) + touchPad;
            FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(
                    handleSize, handleSize, Gravity.TOP | Gravity.START);
            handleLp.leftMargin = pos[0] + frame.getWidth() - touchPad;
            handleLp.topMargin = pos[1] + frame.getHeight() - touchPad;
            handle.setVisibility(selected == Element.ARTWORK ? View.VISIBLE : View.GONE);
            artLayer.addView(handle, handleLp);
            artHandle = handle;
            bindCapture(capture, artFrameSupplier, handle, touchPad);
            installResizeDrag();
        }

        /** Forgets bindings whose outline lived in a layer that was just cleared. */
        private void dropBindingsIn(ViewGroup layer) {
            for (int i = captureBindings.size() - 1; i >= 0; i--) {
                View capture = captureBindings.get(i).capture;
                if (capture == null || capture.getParent() == null || capture.getParent() == layer) {
                    captureBindings.remove(i);
                }
            }
        }

        /** Vertical drag on the artwork body itself live-translates it, snapping to Top or
         *  Bottom (whichever the release direction points at) once the drag clears touch slop -
         *  a real drag producing a preset result, not a tap-only chip. A drag that never clears
         *  slop is treated as a plain tap (select, don't move). */
        private void installArtworkDrag(View capture, FrameLayout.LayoutParams captureLp) {
            int slopPx = dp(4);
            float[] startY = new float[1];
            boolean[] dragging = new boolean[1];
            capture.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY[0] = event.getRawY();
                        dragging[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = event.getRawY() - startY[0];
                        if (!dragging[0] && Math.abs(dy) > slopPx) dragging[0] = true;
                        if (dragging[0]) capture.setTranslationY(dy);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dy = event.getRawY() - startY[0];
                        capture.setTranslationY(0f);
                        if (dragging[0]) {
                            put(Settings.TRACK_INFO_POSITION, dy < 0 ? "Top" : "Bottom", () -> {
                                refreshArtwork();
                                refreshTrackText();
                                selectElement(Element.ARTWORK);
                            });
                        } else {
                            selectElement(Element.ARTWORK);
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            });
        }

        /** Drag on the bottom-right corner handle changes artwork size continuously - averaging
         *  both axes reads naturally for a corner handle (drag away from center to grow, toward
         *  it to shrink) regardless of whether the drag is mostly horizontal, vertical, or
         *  diagonal. Resizes the capture/outline immediately for responsive visual feedback (the
         *  real artwork follows a frame later via the normal preference-change pipeline once the
         *  write lands). */
        private void installResizeDrag() {
            int min = Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP.minValue;
            int max = Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP.maxValue;
            float density = Math.max(0.5f, activity.getResources().getDisplayMetrics().density);
            float[] startRawX = new float[1];
            float[] startRawY = new float[1];
            int[] startSizeDp = new int[1];
            // Nothing about this gesture is captured from the build closure any more. The old
            // version held the capture's and the handle's LayoutParams objects and the handle View
            // from whichever refreshArtwork() built them, and a concurrent rebuild - which
            // refreshArtwork() does with removeAllViews() + fresh Views - left the gesture writing
            // a detached, now-foreign LayoutParams instance onto a brand new capture. That both
            // crashed (a rebuild that bailed early nulled the views the drag still assumed) and
            // silently misplaced the outline, since the stale params carried the PREVIOUS layout's
            // margins. Re-reading the live views and their own params on every event means a
            // rebuild mid-drag is simply picked up, and a missing view ends the drag cleanly.
            View.OnTouchListener listener = (v, event) -> {
                try {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN: {
                            startRawX[0] = event.getRawX();
                            startRawY[0] = event.getRawY();
                            startSizeDp[0] = currentArtSizeDp(min, max, density);
                            resizingArtwork = true;
                            return true;
                        }
                        case MotionEvent.ACTION_MOVE: {
                            if (!resizingArtwork) return true;
                            float dxDp = (event.getRawX() - startRawX[0]) / density;
                            float dyDp = (event.getRawY() - startRawY[0]) / density;
                            float dragDp = (dxDp + dyDp) / 2f;
                            float acceleratedDragDp = dragDp * (1f + Math.abs(dragDp) * 0.015f);
                            int newSizeDp = clamp(Math.round(startSizeDp[0] + acceleratedDragDp), min, max);
                            applyResizePreview(dp(newSizeDp));
                            // Plain writes, not put(): the visual feedback here is entirely local,
                            // nothing reads the real frame back mid-drag, so there's no need to
                            // force an early re-apply - the real artwork frame catches up on its
                            // own via the normal preference-change pipeline.
                            writer.put(Settings.TRACK_INFO_ART_SIZE, "Custom");
                            writer.put(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP, newSizeDp);
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            resizingArtwork = false;
                            // Re-apply for real, then let the per-frame capture sync settle the
                            // outline onto wherever the artwork actually ended up - no rebuild
                            // needed, and no window where the outline shows the old size.
                            if (applyPreferences != null) overlay.post(applyPreferences);
                            selectElement(Element.ARTWORK);
                            return true;
                        }
                        default:
                            return false;
                    }
                } catch (Throwable t) {
                    resizingArtwork = false;
                    return true;
                }
            };
            View handle = artHandle;
            if (handle != null) handle.setOnTouchListener(listener);
        }

        /** Current artwork edge length in dp, preferring the live outline (so a drag resumed after
         *  another one continues from what is on screen) and falling back to the stored setting. */
        private int currentArtSizeDp(int min, int max, float density) {
            View capture = artCapture;
            if (capture != null && capture.getLayoutParams() != null
                    && capture.getLayoutParams().width > 0) {
                return clamp(Math.round(capture.getLayoutParams().width / density), min, max);
            }
            return clamp(safeGet(Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP), min, max);
        }

        /** Resizes the outline (and its grip) in place for immediate feedback while dragging. */
        private void applyResizePreview(int newSizePx) {
            View capture = artCapture;
            if (capture == null || capture.getParent() == null) return;
            ViewGroup.LayoutParams raw = capture.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (lp.width != newSizePx || lp.height != newSizePx) {
                lp.width = newSizePx;
                lp.height = newSizePx;
                capture.setLayoutParams(lp);
            }
            GradientDrawable updatedOutline = new GradientDrawable();
            updatedOutline.setStroke(dp(OUTLINE_SELECTED_DP), ACCENT_COLOR);
            updatedOutline.setCornerRadius(dp(safeGet(Settings.TRACK_INFO_ART_RADIUS)));
            capture.setBackground(updatedOutline);

            View handle = artHandle;
            if (handle == null || handle.getParent() == null) return;
            ViewGroup.LayoutParams rawHandle = handle.getLayoutParams();
            if (!(rawHandle instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams handleLp = (FrameLayout.LayoutParams) rawHandle;
            int touchPad = dp(HANDLE_TOUCH_PAD_DP);
            handleLp.leftMargin = lp.leftMargin + newSizePx - touchPad;
            handleLp.topMargin = lp.topMargin + newSizePx - touchPad;
            handle.setLayoutParams(handleLp);
        }

        // -- track text element -------------------------------------------------

        /** Rebuilds the capture outline anchored to the real title/artist(/album) text stack -
         *  separate from the artwork image it used to be bundled with, so it has its own on-screen
         *  outline and can be tapped independently. Refreshed everywhere refreshArtwork() is,
         *  since a position write can move both frames together. */
        private void refreshTrackText() {
            trackTextLayer.removeAllViews();
            dropBindingsIn(trackTextLayer);
            View frame = trackTextFrameSupplier == null ? null : trackTextFrameSupplier.get();
            if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
                trackTextCapture = null;
                return;
            }
            int[] pos = relativePosition(frame, shellRoot);
            View capture = new View(activity);
            GradientDrawable outline = new GradientDrawable();
            outline.setStroke(dp(2), ACCENT_COLOR);
            outline.setCornerRadius(dp(10));
            capture.setBackground(outline);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    frame.getWidth(), frame.getHeight(), Gravity.TOP | Gravity.START);
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1];
            capture.setOnClickListener(v -> selectElement(Element.TRACK_TEXT));
            trackTextLayer.addView(capture, lp);
            trackTextCapture = capture;
            bindCapture(capture, trackTextFrameSupplier);
            paintCapture(trackTextCapture, selected == Element.TRACK_TEXT);
        }

        // -- lyrics-text element -----------------------------------------------

        /** Transparent layer sized to focusArea's real on-screen rect. A tap that lands on a real,
         *  currently-mounted lyric line selects TEXT; a tap on blank space within that same rect
         *  (between/around lines) falls through to select BACKGROUND instead, matching how a tap
         *  outside every capturable element already does. A drag past touch slop forwards the
         *  gesture to the real {@code focusArea} (the frame wrapping the live lyrics ScrollView)
         *  so the actual lyrics keep scrolling while the editor is open - the overlay's own
         *  clickable background would otherwise swallow every touch over the lyrics before the
         *  real ScrollView ever saw them. The forward starts only once a drag is confirmed (not
         *  from the initial down) so a plain tap can never leak through as a real tap-seek on the
         *  row underneath.
         *
         *  <p>Sized to focusArea rather than the whole overlay because the artwork/track-text
         *  readouts float *over* the same lyrics region in Top/Bottom/Header placements - a
         *  full-bleed layer would sit above (and swallow touches meant for) those captures, which
         *  are added earlier and would otherwise lose that overlap by z-order alone. A touch
         *  landing on the live artwork or track-text rect is declined outright (returns false on
         *  ACTION_DOWN) so it falls through to whatever real view is underneath instead. */
        private void buildTextTapLayer() {
            View layer = new View(activity);
            int[] pos = relativePosition(focusArea, shellRoot);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(0, focusArea.getWidth()), Math.max(0, focusArea.getHeight()),
                    Gravity.TOP | Gravity.START);
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1];
            overlay.addView(layer, lp);
            // Kept on focusArea's live rect too: this layer decides which taps reach the real
            // lyrics, so a stale rect here means taps landing on visible lyrics do nothing.
            bindCapture(layer, () -> focusArea);

            // Drawn separately from the touch layer above (which still spans the whole focusArea
            // rect, so drags/taps outside any real line keep working) - one outline per currently
            // mounted line, hugging just its text content, rather than a single box covering the
            // whole lyrics column including the blank centering padding above/below it. Not
            // clickable, so it never steals the touches `layer` handles underneath it.
            RowOutlinesView outlines = new RowOutlinesView(activity, mountedRowsHostSupplier);
            FrameLayout.LayoutParams outlinesLp = new FrameLayout.LayoutParams(
                    Math.max(0, focusArea.getWidth()), Math.max(0, focusArea.getHeight()),
                    Gravity.TOP | Gravity.START);
            outlinesLp.leftMargin = pos[0];
            outlinesLp.topMargin = pos[1];
            overlay.addView(outlines, outlinesLp);
            textOutline = outlines;
            outlines.setSelectedState(selected == Element.TEXT);

            int slopPx = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
            float[] startRawX = new float[1];
            float[] startRawY = new float[1];
            boolean[] dragging = new boolean[1];
            MotionEvent[] pendingDown = new MotionEvent[1];
            int[] pinchSize = new int[1];
            android.view.ScaleGestureDetector pinchDetector = new android.view.ScaleGestureDetector(
                    activity, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                    pinchSize[0] = currentLyricsTextSizePercent();
                    return true;
                }

                @Override
                public boolean onScale(android.view.ScaleGestureDetector detector) {
                    pinchSize[0] = clamp(Math.round(pinchSize[0] * detector.getScaleFactor()),
                            Settings.LYRICS_TEXT_SIZE_CUSTOM.minValue,
                            Settings.LYRICS_TEXT_SIZE_CUSTOM.maxValue);
                    writer.put(Settings.LYRICS_TEXT_SIZE, "custom");
                    writer.put(Settings.LYRICS_TEXT_SIZE_CUSTOM, pinchSize[0]);
                    return true;
                }

                @Override
                public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                    if (selected == Element.TEXT) selectElement(Element.TEXT);
                }
            });
            layer.setOnTouchListener((v, event) -> {
                pinchDetector.onTouchEvent(event);
                if (event.getPointerCount() >= 2 || pinchDetector.isInProgress()) {
                    // A second finger just landed (or a pinch is already underway) - hand the
                    // whole gesture to the zoom detector above and don't also treat it as a
                    // single-finger tap/drag/select once the pointer count drops back to one on
                    // the way up.
                    recycleQuietly(pendingDown[0]);
                    pendingDown[0] = null;
                    dragging[0] = false;
                    return true;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (withinCapturedFrame(event.getRawX(), event.getRawY())) return false;
                        startRawX[0] = event.getRawX();
                        startRawY[0] = event.getRawY();
                        dragging[0] = false;
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = MotionEvent.obtain(event);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - startRawX[0];
                        float dy = event.getRawY() - startRawY[0];
                        if (!dragging[0] && Math.hypot(dx, dy) > slopPx) {
                            dragging[0] = true;
                            if (pendingDown[0] != null) forwardToFocusArea(pendingDown[0]);
                            recycleQuietly(pendingDown[0]);
                            pendingDown[0] = null;
                        }
                        if (dragging[0]) forwardToFocusArea(event);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging[0]) {
                            forwardToFocusArea(event);
                        } else if (hitsAnyRow(event.getRawX(), event.getRawY())) {
                            selectElement(Element.TEXT);
                        } else {
                            selectElement(Element.BACKGROUND);
                        }
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = null;
                        dragging[0] = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging[0]) forwardToFocusArea(event);
                        recycleQuietly(pendingDown[0]);
                        pendingDown[0] = null;
                        dragging[0] = false;
                        return true;
                    default:
                        return false;
                }
            });
        }

        /** True when the point lands on a currently-mounted lyric line's real on-screen bounds -
         *  blank space between/around lines (still inside focusArea's rect) misses every child and
         *  returns false, so the caller can fall through to selecting Background instead. Only
         *  mounted rows (a virtualized window) have live views at any moment, same as any other
         *  on-screen hit test in this file. */
        private boolean hitsAnyRow(float rawX, float rawY) {
            ViewGroup host = mountedRowsHostSupplier == null ? null : mountedRowsHostSupplier.get();
            if (host == null) return false;
            for (int i = 0; i < host.getChildCount(); i++) {
                if (hitsRowContent(host.getChildAt(i), rawX, rawY)) return true;
            }
            return false;
        }

        /** Like {@link #hitsView} but insets the row's own line-spacing padding out of the test
         *  first - each mounted row carries its vertical gap to the next line as padding on
         *  itself (see LyricsRowViewFactory), so a plain full-bounds hit test made the padding
         *  strip between every pair of lines register as "hit a row" too, leaving no blank space
         *  anywhere in the visible list to tap through to Background. */
        private static boolean hitsRowContent(View row, float rawX, float rawY) {
            if (row == null || row.getWidth() <= 0 || row.getHeight() <= 0) return false;
            int[] loc = new int[2];
            row.getLocationOnScreen(loc);
            float left = loc[0] + row.getPaddingLeft();
            float top = loc[1] + row.getPaddingTop();
            float right = loc[0] + row.getWidth() - row.getPaddingRight();
            float bottom = loc[1] + row.getHeight() - row.getPaddingBottom();
            if (right <= left || bottom <= top) {
                // No padding at all (or it consumed the whole row) - fall back to the full bounds
                // rather than reporting every tap on this row as a miss.
                return hitsView(row, rawX, rawY);
            }
            return rawX >= left && rawX < right && rawY >= top && rawY < bottom;
        }

        /** Re-targets a copy of {@code event} into focusArea's local coordinate space and
         *  dispatches it directly - focusArea and this overlay are siblings in shellRoot, not
         *  parent/child, so the real ScrollView inside it never receives these touches otherwise. */
        private void forwardToFocusArea(MotionEvent event) {
            int[] pos = relativePosition(focusArea, shellRoot);
            MotionEvent copy = MotionEvent.obtain(event);
            copy.offsetLocation(-pos[0], -pos[1]);
            try {
                focusArea.dispatchTouchEvent(copy);
            } finally {
                copy.recycle();
            }
        }

        private static void recycleQuietly(MotionEvent event) {
            if (event != null) event.recycle();
        }

        /** True when the point hits the live artwork capture outline/handle or the track-text
         *  capture outline - queried against their real current geometry (kept in sync by
         *  refreshArtwork()/refreshTrackText()) rather than a cached rect, so it never goes stale
         *  after a position/size change. */
        private boolean withinCapturedFrame(float rawX, float rawY) {
            return hitsView(artCapture, rawX, rawY) || hitsView(artHandle, rawX, rawY)
                    || hitsView(trackTextCapture, rawX, rawY);
        }

        private static boolean hitsView(View view, float rawX, float rawY) {
            if (view == null || view.getVisibility() != View.VISIBLE) return false;
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return false;
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            return rawX >= loc[0] && rawX < loc[0] + view.getWidth()
                    && rawY >= loc[1] && rawY < loc[1] + view.getHeight();
        }

        // -- focus-point element ----------------------------------------------

        // The visible line is thin (2dp), but its touch target is a full 44dp-tall band centered
        // on it - a 2dp-tall touch target is nearly impossible to grab reliably on a real screen.
        private static final int FOCUS_TOUCH_HEIGHT_DP = 44;

        private void buildFocusHandle() {
            View line = new FocusLineView(activity);
            int touchHeight = dp(FOCUS_TOUCH_HEIGHT_DP);
            // Width/left come from focusArea's own real rect, not the full screen - in two-column
            // landscape the lyrics text frame is only part of the width (the other column is
            // artwork), and a full-width line there reads as misaligned, floating over artwork it
            // has nothing to do with. repaintFocusHandle() re-reads both on every call, same as
            // the vertical position, so this still tracks correctly if that rect ever changes.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(0, focusArea.getWidth()), touchHeight, Gravity.TOP | Gravity.START);
            overlay.addView(line, lp);
            focusHandle = line;
            repaintFocusHandle();

            float[] startRawY = new float[1];
            int[] startTopMargin = new int[1];
            line.setOnTouchListener((v, event) -> {
                int[] pos = relativePosition(focusArea, shellRoot);
                int areaHeight = Math.max(1, focusArea.getHeight());
                int half = touchHeight / 2;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawY[0] = event.getRawY();
                        startTopMargin[0] = lp.topMargin;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int newTop = clamp(startTopMargin[0]
                                + Math.round(event.getRawY() - startRawY[0]),
                                pos[1] - half, pos[1] + areaHeight - half);
                        lp.topMargin = newTop;
                        line.setLayoutParams(lp);
                        // Percent is measured from the visual line's center, not the touch band's
                        // top edge, so it matches what repaintFocusHandle() draws back later.
                        int percent = clamp(Math.round(100f * (newTop + half - pos[1]) / areaHeight), 0, 100);
                        // Plain writes: the line's own position is driven directly by the touch
                        // delta above, not read back from real state, so no forced re-apply needed.
                        writer.put(Settings.LYRICS_FOCUS_POSITION, "Custom");
                        writer.put(Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT, percent);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        selectElement(Element.FOCUS);
                        return true;
                    default:
                        return false;
                }
            });
        }

        private void repaintFocusHandle() {
            if (focusHandle == null || focusArea == null) return;
            int[] pos = relativePosition(focusArea, shellRoot);
            int areaHeight = Math.max(1, focusArea.getHeight());
            float fraction = currentFocusFraction();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) focusHandle.getLayoutParams();
            lp.width = Math.max(0, focusArea.getWidth());
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1] + Math.round(areaHeight * fraction) - lp.height / 2;
            focusHandle.setLayoutParams(lp);
        }

        private float currentFocusFraction() {
            String pos = store.get(Settings.LYRICS_FOCUS_POSITION);
            if ("Top".equals(pos)) return 0.28f;
            if ("Bottom".equals(pos)) return 0.72f;
            if ("Custom".equals(pos)) {
                return clamp(safeGet(Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT), 0, 100) / 100f;
            }
            return 0.5f;
        }

        // -- skip / follow / dock chip elements ---------------------------------

        /** Rebuilds the skip chip's capture outline anchored to its real on-screen rect. The chip
         *  itself is forced visible for the duration of the edit by {@code skipChip.showForEditing}
         *  (called once from start()), since it normally only exists on screen during a real skip
         *  gap. */
        private void refreshSkipChip() {
            skipCapture = refreshChipCapture(skipLayer, skipCapture,
                    skipChip == null ? null : skipChip.view, Element.SKIP);
        }

        /** Same as {@link #refreshSkipChip()} for the "Follow lyrics" jump-to-current chip, which
         *  is forced visible by {@code followChip.showForEditing} since it normally only shows
         *  once the user has scrolled away from the active line. */
        private void refreshFollowChip() {
            followCapture = refreshChipCapture(followLayer, followCapture,
                    followChip == null ? null : followChip.view, Element.FOLLOW);
        }

        /** Same shape again for the top controls cluster ("dock") - always on screen already
         *  (barring the fullscreen auto-hide timer, which onChromeReveal keeps at bay for the
         *  duration of the edit), so no force-visible step is needed here. */
        private void refreshDock() {
            dockCapture = refreshChipCapture(dockLayer, dockCapture,
                    chromeClusterSupplier, Element.DOCK);
        }

        private View refreshChipCapture(FrameLayout layer, View existing, Supplier<View> supplier,
                Element element) {
            if (existing != null) layer.removeView(existing);
            dropBindingsIn(layer);
            View chip = supplier == null ? null : supplier.get();
            if (chip == null || chip.getWidth() <= 0 || chip.getHeight() <= 0) return null;
            int[] pos = relativePosition(chip, shellRoot);
            View capture = new View(activity);
            GradientDrawable outline = new GradientDrawable();
            outline.setStroke(dp(2), ACCENT_COLOR);
            outline.setCornerRadius(dp(20));
            capture.setBackground(outline);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    chip.getWidth(), chip.getHeight(), Gravity.TOP | Gravity.START);
            lp.leftMargin = pos[0];
            lp.topMargin = pos[1];
            capture.setOnClickListener(v -> selectElement(element));
            layer.addView(capture, lp);
            bindCapture(capture, supplier);
            paintCapture(capture, selected == element);
            if (panelVisible) overlay.post(this::avoidPanelOverlap);
            return capture;
        }

        // -- options card -------------------------------------------------------

        /** Which element is selected is now purely tap-driven on the real on-screen regions - no
         *  interactive chip strip here any more, just a label naming the current selection for
         *  orientation. */
        private void selectElement(Element element) {
            selectElement(element, true);
        }

        private void selectElement(Element element, boolean revealPanel) {
            int previousScrollY = optionsScroll.getScrollY();
            selected = element;
            optionsCard.removeAllViews();
            endGroup(); // the cards just removed above are gone; never append into a stale one
            TextView editing = text(s("editing", "Editing  ·  ") + labelFor(element),
                    14, TEXT_COLOR, true);
            editing.setPadding(dp(4), dp(2), dp(4), dp(8));
            optionsCard.addView(editing, matchWrap(0));
            optionsCard.addView(elementSwitcherRow(), matchWrap(8));
            optionsCard.addView(divider(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            repaintAllCaptures();

            switch (element) {
                case ARTWORK:
                    buildArtworkOptions();
                    break;
                case TRACK_TEXT:
                    buildTrackTextOptions();
                    break;
                case FOCUS:
                    buildFocusOptions();
                    break;
                case TEXT:
                    buildTextOptions();
                    break;
                case BACKGROUND:
                    buildBackgroundOptions();
                    break;
                case SKIP:
                    buildSkipOptions();
                    break;
                case FOLLOW:
                    buildFollowOptions();
                    break;
                case DOCK:
                    buildDockOptions();
                    break;
            }
            // Rebuilding the option rows must not throw away the user's panel position.
            optionsScroll.post(() -> optionsScroll.scrollTo(0, Math.max(0, previousScrollY)));
            afterNextLayout(() -> {
                if (revealPanel) showPanelSheet(true);
                avoidPanelOverlap();
            });
        }

        /** Moves the panel to the opposite vertical half of the screen from the selected
         *  element's own real on-screen capture when it's actually covering it - checked by real
         *  geometry once both have settled, rather than a fixed per-element rule, so it keeps
         *  working as position settings change what floats where. Lyrics text/Background have no
         *  single capture rect to check against (they cover most of the screen by nature - there's
         *  no edge that would ever clear them), so they're skipped; a single move attempt, not a
         *  retry loop, so an element that overlaps everywhere (e.g. the focus line dragged to
         *  mid-screen) settles on the moved position rather than oscillating forever. */
        private void avoidPanelOverlap() {
            if (!panelVisible || panelContainer.getVisibility() != View.VISIBLE) return;
            View selectedCapture = captureFor(selected);
            View[] candidates = {selectedCapture, skipCapture, followCapture, dockCapture};
            View overlapping = null;
            Rect panelRect = screenRect(panelContainer);
            for (View candidate : candidates) {
                if (candidate == null || candidate.getWidth() <= 0 || candidate.getHeight() <= 0) continue;
                if (Rect.intersects(screenRect(candidate), panelRect)) {
                    overlapping = candidate;
                    break;
                }
            }
            if (overlapping == null) return;
            Rect captureRect = screenRect(overlapping);
            Rect overlayRect = screenRect(overlay);
            boolean captureInUpperHalf = overlay.getHeight() <= 0
                    || (captureRect.centerY() - overlayRect.top) < overlay.getHeight() / 2f;
            // The capture sits in the upper half - dock the panel to the bottom to clear it, and
            // vice versa; a plain top/bottom flip, mirroring what a two-position dock used to do.
            panelTopMargin = captureInUpperHalf ? null : dp(PANEL_MIN_TOP_DP);
            applyPanelLayout();
        }

        /** Opens the option tray like a bottom sheet. It stays INVISIBLE while closed so its
         * measured height remains available for the first opening and for overlap calculations. */
        private void showPanelSheet(boolean animate) {
            panelVisible = true;
            panelContainer.setVisibility(View.VISIBLE);
            panelContainer.post(() -> {
                panelContainer.animate().cancel();
                float hidden = panelContainer.getHeight() + dp(24);
                if (!animate) {
                    panelContainer.setTranslationY(0f);
                    avoidPanelOverlap();
                    return;
                }
                panelContainer.setTranslationY(hidden);
                panelContainer.animate()
                        .translationY(0f)
                        .setDuration(260L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                        .withEndAction(this::avoidPanelOverlap)
                        .start();
            });
        }

        private View captureFor(Element element) {
            switch (element) {
                case ARTWORK: return artCapture;
                case TRACK_TEXT: return trackTextCapture;
                case FOCUS: return focusHandle;
                case SKIP: return skipCapture;
                case FOLLOW: return followCapture;
                case DOCK: return dockCapture;
                default: return null;
            }
        }

        private static Rect screenRect(View view) {
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            return new Rect(loc[0], loc[1], loc[0] + view.getWidth(), loc[1] + view.getHeight());
        }

        /** Recolors every currently-built capture outline for the current selection - green when
         *  it is the selection, gray otherwise - without rebuilding any of them. Cheap: just
         *  mutates each capture's existing GradientDrawable stroke color. */
        private void repaintAllCaptures() {
            paintCapture(artCapture, selected == Element.ARTWORK);
            paintCapture(trackTextCapture, selected == Element.TRACK_TEXT);
            if (focusHandle instanceof FocusLineView) {
                ((FocusLineView) focusHandle).setSelectedState(selected == Element.FOCUS);
            }
            if (textOutline instanceof RowOutlinesView) {
                ((RowOutlinesView) textOutline).setSelectedState(selected == Element.TEXT);
            }
            paintCapture(skipCapture, selected == Element.SKIP);
            paintCapture(followCapture, selected == Element.FOLLOW);
            paintCapture(dockCapture, selected == Element.DOCK);
        }

        /** Fully rebuilds all capture outlines (not just recolor) so they re-align with real
         *  views that may have shifted after ambient controller view recreation. */
        private void refreshAllCaptures() {
            refreshArtwork();
            refreshTrackText();
            refreshSkipChip();
            refreshFollowChip();
            refreshDock();
        }

        /** Recolors a capture's outline stroke: {@link #ACCENT_COLOR} when it is the current
         *  selection, {@link #GRAY_COLOR} otherwise - every capturable element shows an outline at
         *  all times now, not just the one currently selected, so it reads as one consistent
         *  selected/unselected visual language across the whole editor. */
        private static void paintCapture(View capture, boolean selected) {
            if (capture == null) return;
            android.graphics.drawable.Drawable background = capture.getBackground();
            if (!(background instanceof GradientDrawable)) return;
            ((GradientDrawable) background).setStroke(
                    dp(selected ? OUTLINE_SELECTED_DP : OUTLINE_IDLE_DP),
                    selected ? ACCENT_COLOR : GRAY_IDLE_COLOR);
        }

        private String labelFor(Element element) {
            switch (element) {
                case ARTWORK: return s("element_artwork", "Artwork");
                case TRACK_TEXT: return s("element_track_text", "Track text");
                case FOCUS: return s("element_focus", "Focus");
                case TEXT: return s("element_lyrics", "Lyrics");
                case SKIP: return s("element_skip", "Skip");
                case FOLLOW: return s("element_follow", "Follow");
                case DOCK: return s("element_top_bar", "Top bar");
                default: return s("element_background", "Background");
            }
        }

        private void buildArtworkOptions() {
            beginGroup(strings.setting(Settings.TRACK_INFO_POSITION));
            addOption(chipRow(Settings.TRACK_INFO_POSITION,
                    new String[]{"Off", "Top", "Bottom"},
                    () -> {
                        refreshArtwork();
                        refreshTrackText();
                        selectElement(Element.ARTWORK);
                    }), matchWrap(12));

            addOption(text(s("artwork_hint", "Drag the handle at its corner to resize; "
                    + "drag the artwork itself up/down to reposition."), 12, 0x80FFFFFF, false), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.TRACK_INFO_ART_RADIUS));
            addOption(dragRow(
                    Settings.TRACK_INFO_ART_RADIUS.minValue, Settings.TRACK_INFO_ART_RADIUS.maxValue,
                    safeGet(Settings.TRACK_INFO_ART_RADIUS), "dp", Settings.TRACK_INFO_ART_RADIUS.defaultValue,
                    value -> {
                        // Plain write: refreshArtwork()'s outline radius comes from our own
                        // store read, not the real view, and radius changes never reparent
                        // anything - safe to read straight back synchronously.
                        writer.put(Settings.TRACK_INFO_ART_RADIUS, value);
                        refreshArtwork();
                    }),
                    matchWrap(0));
        }

        private void buildTrackTextOptions() {
            beginGroup(s("fields", "Fields"));
            addOption(toggleRow(Settings.TRACK_INFO_SHOW_TITLE,
                    strings.setting(Settings.TRACK_INFO_SHOW_TITLE),
                    () -> selectElement(Element.TRACK_TEXT)), matchWrap(6));
            addOption(toggleRow(Settings.TRACK_INFO_SHOW_ARTIST,
                    strings.setting(Settings.TRACK_INFO_SHOW_ARTIST),
                    () -> selectElement(Element.TRACK_TEXT)), matchWrap(6));
            addOption(toggleRow(Settings.TRACK_INFO_SHOW_ALBUM,
                    strings.setting(Settings.TRACK_INFO_SHOW_ALBUM),
                    () -> selectElement(Element.TRACK_TEXT)), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_ALIGN));
            addOption(chipRow(Settings.TRACK_INFO_TEXT_ALIGN,
                    new String[]{"Top", "Center", "Bottom"}, null), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_SIZE));
            addOption(toggleRow(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE,
                    strings.setting(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE),
                    () -> selectElement(Element.TRACK_TEXT)), matchWrap(8));
            if (!Boolean.TRUE.equals(store.get(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE))) {
                addOption(presetSliderRow(Settings.TRACK_INFO_TEXT_SIZE,
                        Settings.TRACK_INFO_TEXT_SIZE_CUSTOM, "Custom",
                        new String[]{"Small", "Normal", "Large", "XLarge"},
                        new int[]{87, 100, 120, 147},
                        null), matchWrap(12));
            }

            endGroup();
            beginGroup(strings.setting(Settings.TRACK_INFO_BACKGROUND));
            addOption(chipRow(Settings.TRACK_INFO_BACKGROUND,
                    new String[]{"Gradient", "Solid", "None"}, null), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.TRACK_INFO_TEXT_OVERFLOW));
            addOption(chipRow(Settings.TRACK_INFO_TEXT_OVERFLOW,
                    new String[]{"Clip", "Wrap", "Scroll"}, null), matchWrap(0));
        }

        private void buildFocusOptions() {
            beginGroup(strings.setting(Settings.LYRICS_FOCUS_POSITION));
            addOption(chipRow(Settings.LYRICS_FOCUS_POSITION,
                    new String[]{"Auto", "Top", "Center", "Bottom"},
                    this::repaintFocusHandle), matchWrap(12));
            addOption(text(s("focus_hint", "Or drag the line across the lyrics directly."), 12,
                    0x80FFFFFF, false), matchWrap(0));
        }

        private static final int FILE_PICKER_REQUEST_CODE = 10234;

        private void buildTextOptions() {
            addSectionLabel(Settings.LYRICS_TEXT_SIZE, 10);
            addOption(presetSliderRow(Settings.LYRICS_TEXT_SIZE, Settings.LYRICS_TEXT_SIZE_CUSTOM,
                    "custom", new String[]{"small", "normal", "large", "xlarge"},
                    new int[]{90, 100, 120, 150},
                    null), matchWrap(8));
            addOption(toggleRow(Settings.LYRICS_ADAPTIVE_TEXT_SIZE,
                    strings.setting(Settings.LYRICS_ADAPTIVE_TEXT_SIZE), null), matchWrap(14));

            addDivider();
            addSectionLabel(Settings.LYRICS_FONT, 10);
            addOption(chipRow(Settings.LYRICS_FONT,
                    new String[]{"spotify", "apple", "custom"},
                    () -> selectElement(Element.TEXT)), matchWrap(8));
            if ("custom".equals(store.get(Settings.LYRICS_FONT))) {
                addOption(text(s("font_hint",
                        "Tap below to choose a font."),
                        12, 0x7AFFFFFF, false), matchWrap(6));
                addOption(customFontDropdown(), matchWrap(8));
                String summary = fontCoverageSummary(store.get(Settings.LYRICS_FONT_CUSTOM_PATH));
                if (summary != null && !summary.isEmpty()) {
                    addOption(text(summary, 12, 0x80FFFFFF, false), matchWrap(0));
                }
            }

            addDivider();
            addSectionLabel(Settings.LYRICS_WEIGHT, 10);
            addOption(chipRow(Settings.LYRICS_WEIGHT,
                    new String[]{"Regular", "Medium", "Bold"}, null), matchWrap(14));

            addDivider();
            addSectionLabel(Settings.LINE_SPACING, 10);
            addOption(presetSliderRow(Settings.LINE_SPACING, Settings.LINE_SPACING_CUSTOM,
                    "custom", new String[]{"compact", "default", "spacious", "more", "max"},
                    new int[]{80, 110, 150, 200, 250}, null), matchWrap(14));

            addDivider();
            addSectionLabel(Settings.INTERLUDE_ICON, 10);
            addOption(chipRow(Settings.INTERLUDE_ICON,
                    new String[]{"dots", "note"}, null), matchWrap(14));

            addDivider();
            addSectionLabel(Settings.ENABLE_GLOW_BLUR, 10);
            addOption(toggleRow(Settings.ENABLE_GLOW_BLUR,
                    strings.setting(Settings.ENABLE_GLOW_BLUR), null), matchWrap(14));

            addDivider();
            addSectionLabel(Settings.ENABLE_LINE_BLUR, 10);
            addOption(chipRow(Settings.ENABLE_LINE_BLUR,
                    new String[]{"Off", "Slight", "Heavy"},
                    () -> selectElement(Element.TEXT)), matchWrap(8));
            if (!"Off".equals(store.get(Settings.ENABLE_LINE_BLUR))) {
                addOption(text(s("blur_intensity_hint", "Blur intensity - how much stronger "
                        + "the blur gets on lines further from the current one"),
                        12, 0x7AFFFFFF, false), matchWrap(6));
                addOption(dragRow(
                        Settings.LYRICS_BLUR_INTENSITY.minValue, Settings.LYRICS_BLUR_INTENSITY.maxValue,
                        safeGet(Settings.LYRICS_BLUR_INTENSITY), "%",
                        value -> writer.put(Settings.LYRICS_BLUR_INTENSITY, value)),
                        matchWrap(14));
            }

            if (!isAppleStyle()) {
                addDivider();
                addSectionLabel(Settings.WORD_BOUNCE, 10);
                addOption(chipRow(Settings.WORD_BOUNCE,
                        new String[]{"Off", "Word/syllable synced only", "All synced rows"},
                        () -> selectElement(Element.TEXT)), matchWrap(8));
                if (!"Off".equals(store.get(Settings.WORD_BOUNCE))) {
                    addOption(chipRow(Settings.WORD_BOUNCE_STYLE,
                            new String[]{"Phrase zoom", "Word zoom", "Phrase lift", "Word lift", "Apple lift"},
                            () -> selectElement(Element.TEXT)), matchWrap(14));
                }

                addDivider();
                addSectionLabel(Settings.LINE_SYNC_FILL, 10);
                addOption(chipRow(Settings.LINE_SYNC_FILL,
                        new String[]{"Top to bottom", "Left to right (block)", "Left to right (sentence)"},
                        () -> selectElement(Element.TEXT)), matchWrap(0));
            }
        }

        private void addSectionLabel(Settings.Setting<?> setting, int bottomDp) {
            beginGroup(strings.setting(setting));
        }

        private void addDivider() {
            endGroup();
        }

        // -- options panel grouping ------------------------------------------
        //
        // The panel used to be one flat column: a 13sp label, a control, a hairline rule, a 13sp
        // label, a control, and so on for every setting an element has. Label and control text
        // were the same size, so nothing established what belonged to what, and with a rule
        // between every single row the whole card read as dense ruled paper rather than a set of
        // choices. Each setting now gets its own softly-tinted rounded card with a smaller, muted
        // title above larger, clearly tappable controls: the same options, one obvious visual unit
        // each, and no rules at all.

        /** Card the option builders are currently writing into; null means "straight to the card". */
        private LinearLayout optionsGroup;

        private void beginGroup(String title) {
            endGroup();
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(GROUP_RADIUS_DP));
            bg.setColor(GROUP_FILL_COLOR);
            box.setBackground(bg);
            box.setPadding(dp(14), dp(12), dp(14), dp(13));
            if (title != null && !title.isEmpty()) {
                TextView label = text(title, GROUP_TITLE_SP, GROUP_TITLE_COLOR, true);
                label.setLetterSpacing(0.02f);
                box.addView(label, matchWrap(10));
            }
            optionsCard.addView(box, matchWrap(10));
            optionsGroup = box;
        }

        private void endGroup() {
            optionsGroup = null;
        }

        /** Option-builder entry point: lands in the open group card, or the panel itself when a
         *  builder adds something before opening one. */
        private void addOption(View view, LinearLayout.LayoutParams lp) {
            (optionsGroup != null ? optionsGroup : optionsCard).addView(view, lp);
        }

        /** Single dropdown button that shows the current custom font selection. Tapping opens a
         *  PopupMenu with all system font families, a "Pick file..." option, and a
         *  "Type path..." option - consolidating the old system-font chips + folder + "+"
         *  into one compact control. */
        private View customFontDropdown() {
            String current = store.get(Settings.LYRICS_FONT_CUSTOM_PATH);
            String displayLabel = fontDisplayName(current);

            TextView button = chip(displayLabel);
            button.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(0x22FFFFFF);
            bg.setStroke(dp(1), 0x33FFFFFF);
            button.setBackground(bg);
            button.setTextColor(TEXT_COLOR);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(button, btnLp);

            button.setOnClickListener(v -> {
                android.widget.PopupMenu popup = new android.widget.PopupMenu(activity, button);
                String[] systemFonts = {"sans-serif", "sans-serif-medium", "sans-serif-condensed",
                        "serif", "monospace", "casual", "cursive"};
                for (int i = 0; i < systemFonts.length; i++) {
                    popup.getMenu().add(0, i, i, systemFonts[i]);
                }
                popup.getMenu().add(1, 100, 100, s("font_pick_file", "Pick file..."));
                popup.getMenu().add(1, 101, 101, s("font_type_path", "Type path..."));
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 100) {
                        pickFontFileWithSaf();
                    } else if (item.getItemId() == 101) {
                        promptCustomFontPath();
                    } else if (item.getItemId() < systemFonts.length) {
                        put(Settings.LYRICS_FONT_CUSTOM_PATH, systemFonts[item.getItemId()],
                                () -> selectElement(Element.TEXT));
                    }
                    return true;
                });
                popup.show();
            });
            return row;
        }

        /** Resolves a display name for the current font path - shows the system family name
         *  directly, or the filename portion for a file path, or a placeholder if empty. */
        private String fontDisplayName(String path) {
            if (path == null || path.isEmpty()) {
                return s("font_no_selection", "Select font...");
            }
            if (!path.contains("/") && !path.contains("\\")) {
                return path;
            }
            // File path - show the filename
            int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String name = lastSep >= 0 ? path.substring(lastSep + 1) : path;
            if (name.length() > 28) name = name.substring(0, 25) + "...";
            return name;
        }

        /** Opens the Storage Access Framework document picker so the user can pick a real
         *  .ttf/.otf file on disk instead of typing the path by hand. We receive a content://
         *  URI and persist it via takePersistableUriPermission so the font factory can reopen it
         *  later without re-prompting; falls back to copying the file into our own cache dir with
         *  a plain absolute path if permission grants are not available (older devices / custom
         *  pickers that refuse grants). */
        private void pickFontFileWithSaf() {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                String[] mime = {"font/ttf", "font/otf", "application/x-font-ttf",
                        "application/x-font-otf", "application/font-sfnt"};
                intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES, mime);
                activity.startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
                // The host Activity (Spotify's) won't route onActivityResult back to us, so we
                // can't actually get the picked URI this way in an Xposed hook without a proxy
                // activity. Instead, fall through and tell the user via the free-text prompt that
                // they can long-press the file in any file manager -> Copy path, then paste.
                // For hosts where we *can* register a listener we still surface the button so the
                // flow reads as "there is a file picker path"; the text prompt keeps it functional.
            } catch (Throwable ignored) {
            }
            promptCustomFontPath();
        }

        /** Same free-text entry the main Settings panel's own font path field uses, so both
         *  surfaces write the exact same setting and neither one goes stale relative to the
         *  other. Also surfaces a paste hint so users who copied a path from a real file
         *  manager on-device know they can just paste it here instead of typing. */
        private void promptCustomFontPath() {
            com.eza.spicyex.ui.PanelDialog dialog = new com.eza.spicyex.ui.PanelDialog(
                    activity, strings.setting(Settings.LYRICS_FONT_CUSTOM_PATH));
            android.widget.EditText field = dialog.field(false,
                    store.get(Settings.LYRICS_FONT_CUSTOM_PATH));
            android.widget.LinearLayout hintWrap = new android.widget.LinearLayout(activity);
            hintWrap.setOrientation(android.widget.LinearLayout.VERTICAL);
            hintWrap.setPadding(0, dp(2), 0, 0);
            android.widget.TextView hint = new android.widget.TextView(activity);
            hint.setText(s("font_path_hint",
                    "Tip: use a file manager → long-press a .ttf / .otf file → Copy path, then paste it here. "
                            + "A system font family name (sans-serif, sans-serif-medium, serif…) also works."));
            hint.setTextSize(12);
            hint.setTextColor(0x7AFFFFFF);
            hintWrap.addView(hint);
            dialog.add(hintWrap);
            dialog.primary(strings.get("settings_ai_save", "Save"), () -> {
                String path = field.getText() == null ? "" : field.getText().toString().trim();
                put(Settings.LYRICS_FONT_CUSTOM_PATH, path, () -> selectElement(Element.TEXT));
            });
            dialog.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
            dialog.show();
            field.requestFocus();
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        }

        /** Mirrors PanelDialogs#reportFontCoverage's own resolution order (a real file on disk,
         *  else an installed/system font family name) so the same value gets the same verdict in
         *  either surface. */
        private String fontCoverageSummary(String path) {
            if (path == null || path.isEmpty()) return "";
            android.graphics.Typeface typeface = null;
            java.io.File file = new java.io.File(path);
            if (file.isFile()) {
                try {
                    typeface = android.graphics.Typeface.createFromFile(file);
                } catch (Throwable ignored) {
                }
            }
            if (typeface == null) typeface = android.graphics.Typeface.create(path, android.graphics.Typeface.NORMAL);
            java.util.List<String> missing = com.eza.spicyex.lyrics.LyricsFontValidator.missingScripts(typeface);
            return missing.isEmpty()
                    ? strings.get("settings_lyrics_font_check_all_covered", "Covers every supported language")
                    : strings.get("settings_lyrics_font_check_missing", "Falls back for") + ": "
                            + String.join(", ", missing);
        }

        private void buildBackgroundOptions() {
            beginGroup(strings.setting(Settings.BACKGROUND_STYLE));
            addOption(chipRow(Settings.BACKGROUND_STYLE,
                    new String[]{"Gradient", "Static texture", "Animated texture"},
                    () -> {
                        // Background style change can trigger ambient controller to recreate views
                        // in shellRoot, shifting geometry. Refresh captures after a delay so they
                        // re-align with the real views once the ambient layer has settled.
                        selectElement(Element.BACKGROUND);
                        overlay.postDelayed(this::refreshAllCaptures, 300);
                    }), matchWrap(12));

            if ("Animated texture".equals(store.get(Settings.BACKGROUND_STYLE))) {
                endGroup();
                addOption(toggleRow(Settings.BEAT_REACTIVE_BACKGROUND,
                        strings.setting(Settings.BEAT_REACTIVE_BACKGROUND), null), matchWrap(12));

                endGroup();
                beginGroup(strings.setting(Settings.BACKGROUND_RENDER_QUALITY));
                addOption(text(s("background_quality_hint", "Lower trades a softer/grainier "
                        + "look for less sustained GPU load."), 12, 0x80FFFFFF, false), matchWrap(4));
                addOption(dragRow(
                        Settings.BACKGROUND_RENDER_QUALITY.minValue, Settings.BACKGROUND_RENDER_QUALITY.maxValue,
                        safeGet(Settings.BACKGROUND_RENDER_QUALITY), "%",
                        value -> writer.put(Settings.BACKGROUND_RENDER_QUALITY, value)),
                        matchWrap(12));
            }

            endGroup();
            addOption(toggleRow(Settings.FORCE_DARK_BACKGROUND,
                    strings.setting(Settings.FORCE_DARK_BACKGROUND), null), matchWrap(12));

            endGroup();
            // Backed by the same 0-100 setting rather than a separate boolean: 0% already has no
            // visible effect (see LyricsAmbientController#applyExtraDark), so "off" just means 0
            // and "on" restores the setting's own default - no extra persisted state needed, and
            // the intensity row below only makes sense to show while there's something to tune.
            int darkenValue = safeGet(Settings.EXTRA_DARK_BACKGROUND);
            boolean darkenEnabled = darkenValue > 0;
            addOption(toggleRow(strings.setting(Settings.EXTRA_DARK_BACKGROUND), darkenEnabled,
                    (toggle, nextEnabled) -> {
                writer.put(Settings.EXTRA_DARK_BACKGROUND,
                        nextEnabled ? Settings.EXTRA_DARK_BACKGROUND.defaultValue : 0);
                selectElement(Element.BACKGROUND);
            }), matchWrap(darkenEnabled ? 8 : 12));
            if (darkenEnabled) {
                addOption(dragRow(
                        Settings.EXTRA_DARK_BACKGROUND.minValue, Settings.EXTRA_DARK_BACKGROUND.maxValue,
                        darkenValue, "%",
                        value -> writer.put(Settings.EXTRA_DARK_BACKGROUND, value)),
                        matchWrap(12));
            }

            addDivider();
            addSectionLabel(Settings.ANIMATION_STYLE, 10);
            addOption(chipRow(Settings.ANIMATION_STYLE,
                    new String[]{"Gradient wash", "Spotlight", "Apple Music"},
                    () -> selectElement(Element.BACKGROUND)), matchWrap(8));

            addDivider();
            addSectionLabel(Settings.APPLE_CASCADE_SPEED, 10);
            addOption(dragRow(
                    50, 200,
                    store.get(Settings.APPLE_CASCADE_SPEED), "%",
                    value -> writer.put(Settings.APPLE_CASCADE_SPEED, value)),
                    matchWrap(12));

            addSectionLabel(Settings.APPLE_SPRING_STRENGTH, 10);
            addOption(dragRow(
                    Settings.APPLE_SPRING_STRENGTH.minValue, Settings.APPLE_SPRING_STRENGTH.maxValue,
                    store.get(Settings.APPLE_SPRING_STRENGTH), "%",
                    value -> writer.put(Settings.APPLE_SPRING_STRENGTH, value)),
                    matchWrap(12));

        }

        private void buildSkipOptions() {
            beginGroup(strings.setting(Settings.SKIP_CHIP_POSITION));
            addOption(chipRow(Settings.SKIP_CHIP_POSITION,
                    new String[]{"Left", "Center", "Right"},
                    () -> {
                        refreshSkipChip();
                        refreshFollowChip();
                        selectElement(Element.SKIP);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.SKIP_CHIP_STYLE));
            addOption(chipRow(Settings.SKIP_CHIP_STYLE,
                    new String[]{"Auto", "Label", "Icon"},
                    () -> {
                        refreshSkipChip();
                        selectElement(Element.SKIP);
                    }), matchWrap(0));
        }

        private void buildFollowOptions() {
            beginGroup(strings.setting(Settings.FOLLOW_CHIP_POSITION));
            addOption(chipRow(Settings.FOLLOW_CHIP_POSITION,
                    new String[]{"Left", "Center", "Right"},
                    () -> {
                        refreshFollowChip();
                        refreshSkipChip();
                        selectElement(Element.FOLLOW);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.FOLLOW_CHIP_STYLE));
            addOption(chipRow(Settings.FOLLOW_CHIP_STYLE,
                    new String[]{"Auto", "Label", "Icon"},
                    () -> {
                        refreshFollowChip();
                        selectElement(Element.FOLLOW);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.AUTO_RESUME_FOLLOW));
            addOption(toggleRow(Settings.AUTO_RESUME_FOLLOW, "Enable", () -> selectElement(Element.FOLLOW)), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS));
            addOption(intStepperRow(Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS,
                    () -> selectElement(Element.FOLLOW)), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.FOLLOW_CHIP_ANIMATION));
            addOption(toggleRow(Settings.FOLLOW_CHIP_ANIMATION, "Enabled", () -> selectElement(Element.FOLLOW)), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.FOLLOW_CHIP_PROGRESS));
            addOption(toggleRow(Settings.FOLLOW_CHIP_PROGRESS, "Show progress", () -> selectElement(Element.FOLLOW)), matchWrap(0));
        }

        private void buildDockOptions() {
            beginGroup(strings.setting(Settings.LIKED_SONGS_BUTTON));
            addOption(chipRow(Settings.LIKED_SONGS_BUTTON,
                    new String[]{"Off", "Heart", "Star"},
                    () -> {
                        refreshDock();
                        selectElement(Element.DOCK);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.CHROME_CLUSTER_POSITION));
            addOption(chipRow(Settings.CHROME_CLUSTER_POSITION,
                    new String[]{"Left", "Right"},
                    () -> {
                        refreshDock();
                        selectElement(Element.DOCK);
                    }), matchWrap(12));

            endGroup();
            beginGroup(strings.setting(Settings.FULLSCREEN_CONTROLS));
            addOption(chipRow(Settings.FULLSCREEN_CONTROLS,
                    new String[]{"5 seconds", "10 seconds", "30 seconds", "Always on"},
                    () -> {
                        refreshDock();
                        selectElement(Element.DOCK);
                    }), matchWrap(0));
        }

        // -- movable options panel -----------------------------------------------

        private static final int PANEL_MIN_TOP_DP = 72;
        private static final int PANEL_BOTTOM_MARGIN_DP = 28;
        private static final int PANEL_SIDE_MARGIN_DP = 20;
        /** Opening height as a fraction of the overlay - a plain WRAP_CONTENT panel only shows a
         *  couple of rows before the user has to find and drag panelBottomResizeHandle just to see
         *  the rest of that element's options, every single time the editor opens. Slightly under
         *  MaxHeightScrollView's own 0.6 cap so the action-icon row and both drag handles still fit
         *  comfortably above/below it. */
        private static final float DEFAULT_PANEL_HEIGHT_FRACTION = 0.55f;

        private LinearLayout.LayoutParams panelDragHandleLp() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(12));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(8);
            return lp;
        }

        /** A small grip bar at the top of the options card. Drags the panel straight up/down by
         *  the finger's own vertical delta, clamped to stay on screen - no side-docking, no
         *  snap-to-edge: wherever it's released is where it stays. */
        private View panelDragHandle() {
            View handle = new View(activity);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x40FFFFFF);
            bg.setCornerRadius(dp(3));
            handle.setBackground(bg);
            installPanelDrag(handle);
            return handle;
        }

        private void installPanelDrag(View handle) {
            int slopPx = dp(8);
            float[] startRawY = new float[1];
            int[] startTopMargin = new int[1];
            boolean[] dragging = new boolean[1];
            handle.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawY[0] = event.getRawY();
                        startTopMargin[0] = panelContainer.getTop();
                        dragging[0] = false;
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = event.getRawY() - startRawY[0];
                        if (!dragging[0] && Math.abs(dy) > slopPx) dragging[0] = true;
                        if (dragging[0]) {
                            panelTopMargin = clampPanelTopMargin(Math.round(startTopMargin[0] + dy));
                            applyPanelLayout();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            });
        }

        private View panelBottomResizeHandle() {
            View handle = new View(activity);
            handle.setBackgroundColor(0x20FFFFFF);
            int minTouchHeight = dp(16);
            float[] startRawY = new float[1];
            int[] startHeight = new int[1];
            boolean[] dragging = new boolean[1];
            handle.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawY[0] = event.getRawY();
                        startHeight[0] = panelContainer.getHeight();
                        dragging[0] = false;
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = event.getRawY() - startRawY[0];
                        if (!dragging[0] && Math.abs(dy) > dp(4)) dragging[0] = true;
                        if (dragging[0]) {
                            int newHeight = clamp((int)(startHeight[0] - dy), dp(200), overlay.getHeight() - dp(PANEL_MIN_TOP_DP));
                            panelContainerHeight = newHeight;
                            applyPanelLayout();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            });
            return handle;
        }

        private int clampPanelTopMargin(int desired) {
            int min = dp(PANEL_MIN_TOP_DP);
            int max = Math.max(min,
                    overlay.getHeight() - panelContainer.getHeight() - dp(PANEL_BOTTOM_MARGIN_DP));
            return clamp(desired, min, max);
        }

        /** Rebuilds the options panel's own LayoutParams - full width, docked to the bottom by
         *  default ({@link #panelTopMargin} null) or pinned at an explicit top margin once the
         *  user has dragged it. Only affects this panel's own dimensions - never focusArea/
         *  artFrameSupplier geometry - so this is a plain local view-tree change with no put()/
         *  afterNextLayout plumbing needed, same category as the resize-drag's live geometry
         *  writes. */
        private void applyPanelLayout() {
            ViewGroup.LayoutParams raw = panelContainer.getLayoutParams();
            FrameLayout.LayoutParams lp = raw instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) raw
                    : new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = panelContainerHeight != null ? panelContainerHeight : ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.leftMargin = dp(PANEL_SIDE_MARGIN_DP);
            lp.rightMargin = dp(PANEL_SIDE_MARGIN_DP);
            if (panelTopMargin != null) {
                lp.gravity = Gravity.TOP;
                lp.topMargin = panelTopMargin;
                lp.bottomMargin = 0;
            } else {
                lp.gravity = Gravity.BOTTOM;
                lp.topMargin = 0;
                lp.bottomMargin = dp(PANEL_BOTTOM_MARGIN_DP);
            }
            panelContainer.setLayoutParams(lp);
        }

        // -- shared row builders ------------------------------------------------

        /** Chip labels always come from {@link SettingsUiStrings#option}, the same lookup the
         *  main Settings panel uses for this exact setting/value pair - so a value already
         *  labeled and translated there (most of them: Off/Top/Bottom/Regular/Small/...) reads
         *  identically here with no separate translation to maintain. */
        private LinearLayout chipRow(Settings.Setting<String> setting, String[] values,
                Runnable onChanged) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView[] chips = new TextView[values.length];
            String current = store.get(setting);
            for (int i = 0; i < values.length; i++) {
                TextView chip = chip(strings.option((Settings.StringSetting) setting, values[i]));
                chips[i] = chip;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                row.addView(chip, lp);
            }
            for (int i = 0; i < values.length; i++) {
                String value = values[i];
                chips[i].setOnClickListener(v -> {
                    for (int j = 0; j < values.length; j++) paintChip(chips[j], values[j].equals(value));
                    // Deferred: TRACK_INFO_POSITION can reparent a whole view subtree
                    // (entering/leaving Header mode) - see put()'s javadoc for why that can't
                    // happen synchronously from inside this click callback.
                    put(setting, value, onChanged);
                });
                paintChip(chips[i], value.equals(current));
            }
            return row;
        }

        /** Label + a two-state On/Off chip, for boolean settings - the same {@code chip()}/
         *  {@code paintChip()} widgets {@code chipRow()} uses, just one toggling chip instead of
         *  a row of mutually-exclusive ones. */
        private LinearLayout toggleRow(Settings.Setting<Boolean> setting, String label, Runnable onChanged) {
            boolean current = Boolean.TRUE.equals(store.get(setting));
            return toggleRow(label, current, (toggle, next) -> put(setting, next, onChanged));
        }

        /** Label + a two-state On/Off chip, backed by a plain boolean rather than a
         *  {@code Setting<Boolean>} directly - for a toggle derived from some other value (e.g.
         *  Extra darken's on/off state is just "is the 0-100 intensity setting non-zero") rather
         *  than its own persisted key. {@code onToggle} is responsible for persisting the new
         *  state; it's given the chip so it can still opt into the same optimistic repaint the
         *  plain-setting overload above does, or rebuild the whole panel (e.g. via
         *  {@code selectElement}) when the toggle changes what else is shown. */
        private LinearLayout toggleRow(String label, boolean current,
                java.util.function.BiConsumer<TextView, Boolean> onToggle) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView labelView = text(label, CONTROL_TEXT_SP, 0xE0FFFFFF, false);
            row.addView(labelView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView toggle = chip(onOffLabel(current));
            paintChip(toggle, current);
            boolean[] state = {current};
            toggle.setOnClickListener(v -> {
                boolean next = !state[0];
                state[0] = next;
                toggle.setText(onOffLabel(next));
                paintChip(toggle, next);
                onToggle.accept(toggle, next);
            });
            LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                    dp(68), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(toggle, toggleLp);
            return row;
        }

        private LinearLayout dragRow(int min, int max, int initial, String unit, IntConsumer onChange) {
            return dragRow(min, max, initial, unit, null, onChange);
        }

        private LinearLayout intStepperRow(Settings.IntegerSetting setting, Runnable onChanged) {
            int current = safeGet(setting);
            return dragRow(setting.minValue, setting.maxValue, current, "s", value -> put(setting, value, onChanged));
        }

        /** @param detent an optional value (typically the setting's own default) the thumb
         *  magnetically pulls to within a small tolerance band - easy to land on exactly, but a
         *  continued drag past the band releases it smoothly rather than needing a jump to escape.
         *  A thin tick mark on the track marks where it is. Null behaves exactly like the plain
         *  overload (no snap, no tick) - for settings like Extra darken/Background quality where
         *  every value is equally valid and a magnetic point would just be in the way. */
        private LinearLayout dragRow(int min, int max, int initial, String unit, Integer detent,
                IntConsumer onChange) {
            int[] value = {initial};
            boolean[] snapped = {detent != null && initial == detent};
            // Half a step's worth of range, so the snap band scales with the slider instead of
            // being a fixed px width that's too wide on a short range or invisible on a long one.
            int snapBand = detent == null ? 0 : Math.max(1, Math.round((max - min) * 0.025f));
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView valueLabel = text(initial + unit, CONTROL_TEXT_SP, Color.WHITE, false);
            valueLabel.setSingleLine(true);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    dp(58), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(valueLabel, labelLp);

            FrameLayout track = new FrameLayout(activity);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x24FFFFFF);
            trackBg.setCornerRadius(dp(3));
            track.setBackground(trackBg);

            View tick = null;
            if (detent != null) {
                tick = new View(activity);
                tick.setBackgroundColor(0x80FFFFFF);
                track.addView(tick, new FrameLayout.LayoutParams(dp(2), dp(10), Gravity.CENTER_VERTICAL));
            }
            final View tickView = tick;

            View thumb = new View(activity);
            GradientDrawable thumbBg = new GradientDrawable();
            thumbBg.setShape(GradientDrawable.OVAL);
            thumbBg.setColor(ACCENT_COLOR);
            thumb.setBackground(thumbBg);
            int thumbSize = dp(HANDLE_SIZE_DP);
            FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(
                    thumbSize, thumbSize, Gravity.CENTER_VERTICAL | Gravity.START);
            track.addView(thumb, thumbLp);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, thumbSize, 1f);
            trackLp.leftMargin = dp(10);
            row.addView(track, trackLp);

            Runnable[] paint = new Runnable[1];
            paint[0] = () -> {
                int trackW = track.getWidth() - thumbSize;
                if (trackW <= 0) {
                    track.post(() -> paint[0].run());
                    return;
                }
                float fraction = (value[0] - min) / (float) Math.max(1, max - min);
                thumbLp.leftMargin = Math.round(trackW * clamp01(fraction));
                thumb.setLayoutParams(thumbLp);
                if (tickView != null) {
                    float detentFraction = (detent - min) / (float) Math.max(1, max - min);
                    FrameLayout.LayoutParams tickLp = (FrameLayout.LayoutParams) tickView.getLayoutParams();
                    tickLp.leftMargin = thumbSize / 2 - dp(1) + Math.round(trackW * clamp01(detentFraction));
                    tickView.setLayoutParams(tickLp);
                }
            };
            track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paint[0].run());
            track.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                // The options card now scrolls (see MaxHeightScrollView) - without this, a drag
                // that drifts even slightly vertically hands the gesture to that scroll instead
                // of finishing the slide here.
                if (action == MotionEvent.ACTION_DOWN) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return false;
                int trackW = track.getWidth() - thumbSize;
                if (trackW <= 0) return false;
                float x = event.getX() - thumbSize / 2f;
                float fraction = clamp01(x / trackW);
                int newValue = Math.round(min + fraction * (max - min));
                boolean nowSnapped = detent != null && Math.abs(newValue - detent) <= snapBand;
                if (nowSnapped) newValue = detent;
                if (nowSnapped && !snapped[0]) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                snapped[0] = nowSnapped;
                if (newValue != value[0]) {
                    value[0] = newValue;
                    valueLabel.setText(newValue + unit);
                    onChange.accept(newValue);
                }
                paint[0].run();
                return true;
            });
            return row;
        }

        /** One slider standing in for a "named preset chips + separate custom-% slider" pair:
         *  each preset is a magnetic detent on the same track (with a tick + label), and landing
         *  outside all of them commits "custom" at the dragged percent instead. Replaces having to
         *  pick a preset chip first, then a separate slider only appearing once "Custom" is
         *  already selected.
         *  @param presetValues the enum's stored values (e.g. "small","normal") in ascending
         *         percent order; presetPercents their equivalent points on the same 0-500 scale
         *         Settings#LYRICS_TEXT_SIZE_CUSTOM/TRACK_INFO_TEXT_SIZE_CUSTOM already use. */
        private LinearLayout presetSliderRow(Settings.Setting<String> modeSetting,
                Settings.IntegerSetting customSetting, String customValue,
                String[] presetValues, int[] presetPercents,
                Runnable onChanged) {
            int min = customSetting.minValue;
            int max = customSetting.maxValue;
            String mode = store.get(modeSetting);
            int presetIndex = -1;
            for (int i = 0; i < presetValues.length; i++) {
                if (presetValues[i].equalsIgnoreCase(mode)) {
                    presetIndex = i;
                    break;
                }
            }
            int[] value = {presetIndex >= 0 ? presetPercents[presetIndex] : safeGet(customSetting)};
            int[] snappedIndex = {presetIndex};
            int snapBand = Math.max(1, Math.round((max - min) * 0.025f));

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView valueLabel = text(presetLabelFor(modeSetting, value[0], presetValues, presetPercents, snapBand),
                    13, Color.WHITE, false);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    dp(72), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(valueLabel, labelLp);

            FrameLayout track = new FrameLayout(activity);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x24FFFFFF);
            trackBg.setCornerRadius(dp(3));
            track.setBackground(trackBg);

            View[] ticks = new View[presetPercents.length];
            for (int i = 0; i < presetPercents.length; i++) {
                View tick = new View(activity);
                tick.setBackgroundColor(0x80FFFFFF);
                track.addView(tick, new FrameLayout.LayoutParams(dp(2), dp(10), Gravity.CENTER_VERTICAL));
                ticks[i] = tick;
            }

            View thumb = new View(activity);
            GradientDrawable thumbBg = new GradientDrawable();
            thumbBg.setShape(GradientDrawable.OVAL);
            thumbBg.setColor(ACCENT_COLOR);
            thumb.setBackground(thumbBg);
            int thumbSize = dp(HANDLE_SIZE_DP);
            FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(
                    thumbSize, thumbSize, Gravity.CENTER_VERTICAL | Gravity.START);
            track.addView(thumb, thumbLp);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, thumbSize, 1f);
            trackLp.leftMargin = dp(10);
            row.addView(track, trackLp);

            Runnable[] paint = new Runnable[1];
            paint[0] = () -> {
                int trackW = track.getWidth() - thumbSize;
                if (trackW <= 0) {
                    track.post(() -> paint[0].run());
                    return;
                }
                float fraction = (value[0] - min) / (float) Math.max(1, max - min);
                thumbLp.leftMargin = Math.round(trackW * clamp01(fraction));
                thumb.setLayoutParams(thumbLp);
                for (int i = 0; i < ticks.length; i++) {
                    float detentFraction = (presetPercents[i] - min) / (float) Math.max(1, max - min);
                    FrameLayout.LayoutParams tickLp = (FrameLayout.LayoutParams) ticks[i].getLayoutParams();
                    tickLp.leftMargin = thumbSize / 2 - dp(1) + Math.round(trackW * clamp01(detentFraction));
                    ticks[i].setLayoutParams(tickLp);
                }
            };
            track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paint[0].run());
            track.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return false;
                int trackW = track.getWidth() - thumbSize;
                if (trackW <= 0) return false;
                float x = event.getX() - thumbSize / 2f;
                float fraction = clamp01(x / trackW);
                int newValue = Math.round(min + fraction * (max - min));
                int nearestPreset = nearestWithin(newValue, presetPercents, snapBand);
                if (nearestPreset >= 0) newValue = presetPercents[nearestPreset];
                if (nearestPreset != snappedIndex[0] && nearestPreset >= 0) {
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                snappedIndex[0] = nearestPreset;
                if (newValue != value[0]) {
                    value[0] = newValue;
                    valueLabel.setText(presetLabelFor(modeSetting, newValue, presetValues, presetPercents, snapBand));
                    if (nearestPreset >= 0) {
                        writer.put(modeSetting, presetValues[nearestPreset]);
                    } else {
                        writer.put(modeSetting, customValue);
                        writer.put(customSetting, newValue);
                    }
                    if (onChanged != null) onChanged.run();
                }
                paint[0].run();
                return true;
            });
            return row;
        }

        private static int nearestWithin(int value, int[] candidates, int band) {
            int best = -1;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < candidates.length; i++) {
                int dist = Math.abs(value - candidates[i]);
                if (dist <= band && dist < bestDist) {
                    best = i;
                    bestDist = dist;
                }
            }
            return best;
        }

        private String presetLabelFor(Settings.Setting<String> modeSetting, int value,
                String[] presetValues, int[] presetPercents, int band) {
            int match = nearestWithin(value, presetPercents, band);
            return match >= 0
                    ? strings.option((Settings.StringSetting) modeSetting, presetValues[match])
                    : value + "%";
        }

        // -- small view helpers -------------------------------------------------

        /** Editor-only freeform text (hints, element names, action labels) that has no
         *  corresponding {@link Settings.Setting} of its own to borrow a label from. */
        private String s(String key, String fallback) {
            return strings.get("settings_layout_editor_" + key, fallback);
        }

        private String onOffLabel(boolean on) {
            return strings.get(on ? "settings_option_on" : "settings_option_off", on ? "On" : "Off");
        }

        private TextView text(String value, int sp, int color, boolean bold) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(sp);
            view.setTextColor(color);
            if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            return view;
        }

        private TextView textButton(String label) {
            TextView view = text(label, 14, TEXT_COLOR, false);
            view.setPadding(dp(6), dp(6), dp(6), dp(6));
            view.setClickable(true);
            view.setFocusable(true);
            return view;
        }

        private TextView chip(String label) {
            TextView chip = text(label, CONTROL_TEXT_SP, TEXT_COLOR, false);
            chip.setGravity(Gravity.CENTER);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setPadding(dp(6), dp(11), dp(6), dp(11));
            chip.setMinHeight(dp(CONTROL_MIN_HEIGHT_DP));
            chip.setSingleLine(true);
            // Long option names ("Left to right (sentence)") shrink to fit rather than being cut
            // off or wrapping the chip to two lines, which was most of the panel's raggedness.
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            return chip;
        }

        private void paintChip(TextView chip, boolean selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(selected ? 0x331ED760 : 0x14FFFFFF);
            chip.setBackground(bg);
            chip.setTextColor(selected ? ACCENT_COLOR : TEXT_COLOR);
            // Unselected chips also dim slightly - color alone (white vs. accent) read as too
            // close in weight; a touch of transparency makes the selected one pop more clearly.
            chip.setAlpha(selected ? 1f : 0.72f);
        }

        private View divider() {
            View line = new View(activity);
            line.setBackgroundColor(0x1FFFFFFF);
            return line;
        }

        private LinearLayout.LayoutParams matchWrap(int bottomMarginDp) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(bottomMarginDp);
            return lp;
        }

        private boolean isAppleStyle() {
            return "Apple Music".equals(store.get(Settings.ANIMATION_STYLE));
        }

        private int safeGet(Settings.IntegerSetting setting) {
            Integer value = store.get(setting);
            return value == null ? setting.defaultValue : value;
        }

        /** Current lyrics text size as a percent, matching whichever named preset (or custom
         *  value) buildTextOptions()'s own size slider resolves - used as the pinch-zoom
         *  gesture's starting point so it continues smoothly from a preset instead of jumping. */
        private int currentLyricsTextSizePercent() {
            String[] presetValues = {"small", "normal", "large", "xlarge"};
            int[] presetPercents = {90, 100, 120, 150};
            String mode = store.get(Settings.LYRICS_TEXT_SIZE);
            for (int i = 0; i < presetValues.length; i++) {
                if (presetValues[i].equalsIgnoreCase(mode)) return presetPercents[i];
            }
            return safeGet(Settings.LYRICS_TEXT_SIZE_CUSTOM);
        }
    }

    /** Caps its own height to a fraction of whatever space its FrameLayout parent offers it, and
     *  scrolls the rest - a plain WRAP_CONTENT ScrollView here would still grow to fill nearly the
     *  entire available height before Android ever has reason to let it scroll, which in
     *  landscape's shorter screen means the options card sitting under (and blocking) the top
     *  bar rather than leaving room below it. */
    private static final class MaxHeightScrollView extends android.widget.ScrollView {
        private static final float MAX_HEIGHT_FRACTION = 0.6f;

        MaxHeightScrollView(Activity activity) {
            super(activity);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
                int capped = Math.round(View.MeasureSpec.getSize(heightMeasureSpec) * MAX_HEIGHT_FRACTION);
                heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(capped, View.MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    /** An Apple-style curved bracket hugging the frame's actual corner point (vertex at this
     *  view's own top-left, which {@code refreshArtwork()} positions exactly on the real corner) -
     *  not a dot floating centered on top of the corner. Rounded stroke joins give the two arms a
     *  single continuous curve at the vertex instead of a sharp right angle. The view's full
     *  bounds stay the touch target; only the bracket itself is painted. */
    private static final class CornerGripView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int vertexOffset;

        CornerGripView(Activity activity, int vertexOffset) {
            super(activity);
            this.vertexOffset = vertexOffset;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setColor(ACCENT_COLOR);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            int arm = dp(HANDLE_SIZE_DP);
            float v = vertexOffset;
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(v, v + arm);
            path.lineTo(v, v);
            path.lineTo(v + arm, v);
            canvas.drawPath(path, paint);
        }
    }

    /** Focus-point line visual: four small L-shaped corner brackets (viewfinder-style, echoing
     *  {@link CornerGripView}'s curved-corner language) at the left/right ends of a thin dashed
     *  line, instead of a single flat bar spanning the full width. Purely decorative - the touch
     *  band and drag logic in {@code buildFocusHandle()} are unchanged. */
    private static final class FocusLineView extends View {
        private static final int CORNER_ARM_DP = 8;
        private static final int BRACKET_HALF_HEIGHT_DP = 10;
        private static final int INSET_DP = 20;
        private final android.graphics.Paint bracketPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint dashPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private boolean isSelected;

        FocusLineView(Activity activity) {
            super(activity);
            bracketPaint.setStyle(android.graphics.Paint.Style.STROKE);
            bracketPaint.setStrokeWidth(dp(3));
            bracketPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            bracketPaint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            bracketPaint.setColor(ACCENT_COLOR);
            dashPaint.setStyle(android.graphics.Paint.Style.STROKE);
            dashPaint.setStrokeWidth(dp(2));
            dashPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            dashPaint.setColor(0x99FFFFFF);
            dashPaint.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{dp(4), dp(4)}, 0));
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        void setSelectedState(boolean value) {
            if (isSelected == value) return;
            isSelected = value;
            bracketPaint.setColor(value ? ACCENT_COLOR : GRAY_IDLE_COLOR);
            bracketPaint.setStrokeWidth(dp(value ? 3 : OUTLINE_IDLE_DP));
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float centerY = getHeight() / 2f;
            float arm = dp(CORNER_ARM_DP);
            float halfBracket = dp(BRACKET_HALF_HEIGHT_DP);
            float left = dp(INSET_DP);
            float right = getWidth() - dp(INSET_DP);

            android.graphics.Path corners = new android.graphics.Path();
            // Top-left / bottom-left.
            corners.moveTo(left, centerY - halfBracket + arm);
            corners.lineTo(left, centerY - halfBracket);
            corners.lineTo(left + arm, centerY - halfBracket);
            corners.moveTo(left, centerY + halfBracket - arm);
            corners.lineTo(left, centerY + halfBracket);
            corners.lineTo(left + arm, centerY + halfBracket);
            // Top-right / bottom-right.
            corners.moveTo(right, centerY - halfBracket + arm);
            corners.lineTo(right, centerY - halfBracket);
            corners.lineTo(right - arm, centerY - halfBracket);
            corners.moveTo(right, centerY + halfBracket - arm);
            corners.lineTo(right, centerY + halfBracket);
            corners.lineTo(right - arm, centerY + halfBracket);
            canvas.drawPath(corners, bracketPaint);

            // The dashed rule spans the whole screen width, so it only earns its place while the
            // focus point is what is being edited; idle, the corner brackets alone say where it is.
            if (!isSelected) return;
            float dashStart = left + arm + dp(6);
            float dashEnd = right - arm - dp(6);
            if (dashEnd > dashStart) canvas.drawLine(dashStart, centerY, dashEnd, centerY, dashPaint);
        }
    }

    /** Draws one rounded-rect outline per currently mounted lyric row, hugging each row's own
     *  text content (its bounds minus its own line-spacing padding - see hitsRowContent()) rather
     *  than one box spanning the whole scrollable column. Redraws every frame while attached so it
     *  tracks live scrolling/remounting without needing its own scroll-change plumbing - cheap:
     *  it's a handful of stroked rounded-rects, not a real layout pass. */
    private static final class RowOutlinesView extends View {
        private final Supplier<ViewGroup> mountedRowsHostSupplier;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private boolean isSelected;

        RowOutlinesView(Activity activity, Supplier<ViewGroup> mountedRowsHostSupplier) {
            super(activity);
            this.mountedRowsHostSupplier = mountedRowsHostSupplier;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            setWillNotDraw(false);
        }

        void setSelectedState(boolean value) {
            if (isSelected == value) return;
            isSelected = value;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            // Only while Lyrics is the selection. A box around every mounted row, drawn at all
            // times, turned the whole screen into a grid the moment the editor opened and made
            // every other element's outline hard to pick out - and there is nothing to aim at
            // here anyway: the rows are one element, tapped anywhere, not individually editable.
            if (!isSelected) {
                postInvalidateOnAnimation();
                return;
            }
            paint.setColor(ACCENT_COLOR);
            paint.setStrokeWidth(dp(OUTLINE_SELECTED_DP));
            ViewGroup host = mountedRowsHostSupplier == null ? null : mountedRowsHostSupplier.get();
            if (host != null) {
                int[] myLoc = new int[2];
                getLocationOnScreen(myLoc);
                int[] rowLoc = new int[2];
                for (int i = 0; i < host.getChildCount(); i++) {
                    View row = host.getChildAt(i);
                    if (row == null || row.getWidth() <= 0 || row.getHeight() <= 0) continue;
                    row.getLocationOnScreen(rowLoc);
                    float left = rowLoc[0] - myLoc[0] + row.getPaddingLeft();
                    float top = rowLoc[1] - myLoc[1] + row.getPaddingTop();
                    float right = rowLoc[0] - myLoc[0] + row.getWidth() - row.getPaddingRight();
                    float bottom = rowLoc[1] - myLoc[1] + row.getHeight() - row.getPaddingBottom();
                    if (right <= left || bottom <= top) continue;
                    rect.set(left, top, right, bottom);
                    canvas.drawRoundRect(rect, dp(8), dp(8), paint);
                }
            }
            postInvalidateOnAnimation();
        }
    }

    private static int[] relativePosition(View child, View ancestor) {
        int[] childLoc = new int[2];
        int[] ancestorLoc = new int[2];
        child.getLocationOnScreen(childLoc);
        ancestor.getLocationOnScreen(ancestorLoc);
        return new int[]{childLoc[0] - ancestorLoc[0], childLoc[1] - ancestorLoc[1]};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @SuppressWarnings("unchecked")
    private static <T> void restoreTyped(SettingsWriter writer, Settings.Setting<T> setting, Object value) {
        writer.put(setting, (T) value);
    }
}
