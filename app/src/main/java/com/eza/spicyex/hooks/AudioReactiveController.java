package com.eza.spicyex.hooks;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Live audio analysis for the beat-reactive background and the instrumental visualizer, read
 * straight from the PCM Spotify hands to {@link AudioTrack#write}.
 *
 * <p>An earlier version attached an {@link android.media.audiofx.Visualizer} to Spotify's audio
 * session. That works, but a Visualizer is audio capture as far as Android is concerned: it
 * needs the microphone permission and can light the privacy indicator. Every sample Spotify
 * plays already passes through this process on its way to the AudioTrack, so it is analysed
 * there - no capture, no permission, no extra audio pipeline.
 *
 * <p>Each reading takes one contiguous window of {@link #WINDOW} mono frames and produces:
 * <ul>
 *   <li>loudness - plain RMS, used to tell "audio is playing" from silence;</li>
 *   <li>beat - low-end energy against its own recent average. A fixed gain on RMS barely moves
 *       on modern, heavily compressed masters; comparing the bass to where it just was turns
 *       each kick into a clear pulse whatever the mix loudness;</li>
 *   <li>spectrum - {@link #BANDS} log-spaced bands from an FFT, each auto-gained against its own
 *       recent peak so quiet and loud tracks both fill the visualizer.</li>
 * </ul>
 * The beat is tracked over every sample instead (see {@link #trackBass}). Only while the lyrics
 * screen wants it ({@link #setListeningEnabled}); spectrum at most ~30 readings a second. Writes run ahead of what is audible by the track's buffer, so each reading is
 * delivered that much later to line up with the sound.
 */
final class AudioReactiveController {
    private static final String TAG = "[SpicyAudioReactive]";
    private static final long MIN_INTERVAL_MS = 33L;
    static final int BANDS = 28;
    private static final int WINDOW = 1024;
    private static final long MAX_ALIGN_DELAY_MS = 400L;
    private static final float MIN_HZ = 40f;
    private static final float MAX_HZ = 14000f;

    interface Listener {
        /** Main thread. {@code spectrum} is a fresh array the receiver may keep. */
        void onAnalysis(float loudness, float beat, float[] spectrum);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile boolean listeningEnabled;
    private boolean started;
    private long lastReadingMs;

    // Analysis state, audio-thread only.
    private final float[] mono = new float[WINDOW];
    private final float[] re = new float[WINDOW];
    private final float[] im = new float[WINDOW];
    private final float[] hann = new float[WINDOW];
    private final float[] bandPeak = new float[BANDS];

    AudioReactiveController(Listener listener) {
        this.listener = listener;
        for (int i = 0; i < WINDOW; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1)));
        }
        java.util.Arrays.fill(bandPeak, 1e-3f);
    }

    /** Called from the lyrics screen's own mount/unmount lifecycle - see NativeSpicyLyricsHook. */
    void setListeningEnabled(boolean enabled) {
        listeningEnabled = enabled;
        if (!enabled) main.post(() -> listener.onAnalysis(0f, 0f, new float[BANDS]));
    }

    void start() {
        if (started) return;
        started = true;
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(byte[])",
                p -> onBytes((AudioTrack) p.thisObject, (byte[]) p.args[0], (int) p.args[1], (int) p.args[2]),
                byte[].class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(byte[],mode)",
                p -> onBytes((AudioTrack) p.thisObject, (byte[]) p.args[0], (int) p.args[1], (int) p.args[2]),
                byte[].class, int.class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(short[])",
                p -> onShorts((AudioTrack) p.thisObject, (short[]) p.args[0], (int) p.args[1], (int) p.args[2]),
                short[].class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(short[],mode)",
                p -> onShorts((AudioTrack) p.thisObject, (short[]) p.args[0], (int) p.args[1], (int) p.args[2]),
                short[].class, int.class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(float[])",
                p -> onFloats((AudioTrack) p.thisObject, (float[]) p.args[0], (int) p.args[1], (int) p.args[2]),
                float[].class, int.class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(ByteBuffer)",
                p -> onBuffer((AudioTrack) p.thisObject, (ByteBuffer) p.args[0], (int) p.args[1]),
                ByteBuffer.class, int.class, int.class));
        safely(() -> XpHooks.findBefore(AudioTrack.class, "write", "audioReactive:write(ByteBuffer,ts)",
                p -> onBuffer((AudioTrack) p.thisObject, (ByteBuffer) p.args[0], (int) p.args[1]),
                ByteBuffer.class, int.class, int.class, long.class));
        XpLog.log(TAG + " AudioTrack#write analysis hooks installed");
    }

    /** One missing overload must not keep the others from being hooked. */
    private static void safely(Runnable install) {
        try {
            install.run();
        } catch (Throwable t) {
            XpLog.log(TAG + " write hook skipped: " + t.getClass().getSimpleName());
        }
    }

    // Whole write, downmixed to mono; grown on demand, audio-thread only.
    private float[] chunk = new float[8192];
    private static final int MAX_CHUNK_FRAMES = 1 << 16;

    private float[] chunk(int frames) {
        if (chunk.length < frames) chunk = new float[Integer.highestOneBit(frames - 1) << 1];
        return chunk;
    }

    private boolean listening(AudioTrack track) {
        return (listeningEnabled || AudioDebug.watching()) && track != null
                && !AdMusicPlayer.isOwnTrack(track);
    }

    private void onBytes(AudioTrack track, byte[] data, int offset, int size) {
        AudioDebug.WRITE_CALLS[0].incrementAndGet();
        if (data == null || size <= 0 || !listening(track)) return;
        try {
            int encoding = track.getAudioFormat();
            if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                int channels = Math.max(1, track.getChannelCount());
                int frames = Math.min(MAX_CHUNK_FRAMES, size / channels);
                float[] out = chunk(frames);
                for (int f = 0; f < frames; f++) {
                    float sum = 0f;
                    for (int c = 0; c < channels; c++) {
                        sum += ((data[offset + f * channels + c] & 0xFF) - 128) / 128f;
                    }
                    out[f] = sum / channels;
                }
                process(track, frames);
            } else {
                ByteBuffer view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                fillFromBytes(track, view, offset, size, encoding == AudioFormat.ENCODING_PCM_FLOAT);
            }
        } catch (Throwable ignored) {
        }
    }

    private void onShorts(AudioTrack track, short[] data, int offset, int count) {
        AudioDebug.WRITE_CALLS[1].incrementAndGet();
        if (data == null || count <= 0 || !listening(track)) return;
        try {
            int channels = Math.max(1, track.getChannelCount());
            int frames = Math.min(MAX_CHUNK_FRAMES, count / channels);
            float[] out = chunk(frames);
            for (int f = 0; f < frames; f++) {
                float sum = 0f;
                for (int c = 0; c < channels; c++) sum += data[offset + f * channels + c] / 32768f;
                out[f] = sum / channels;
            }
            process(track, frames);
        } catch (Throwable ignored) {
        }
    }

    private void onFloats(AudioTrack track, float[] data, int offset, int count) {
        AudioDebug.WRITE_CALLS[2].incrementAndGet();
        if (data == null || count <= 0 || !listening(track)) return;
        try {
            int channels = Math.max(1, track.getChannelCount());
            int frames = Math.min(MAX_CHUNK_FRAMES, count / channels);
            float[] out = chunk(frames);
            for (int f = 0; f < frames; f++) {
                float sum = 0f;
                for (int c = 0; c < channels; c++) sum += data[offset + f * channels + c];
                out[f] = sum / channels;
            }
            process(track, frames);
        } catch (Throwable ignored) {
        }
    }

    /** Absolute reads from the buffer's current position; the write itself is untouched. */
    private void onBuffer(AudioTrack track, ByteBuffer buffer, int size) {
        AudioDebug.WRITE_CALLS[3].incrementAndGet();
        if (buffer == null || size <= 0 || !listening(track)) return;
        try {
            ByteBuffer view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            fillFromBytes(track, view, buffer.position(), size,
                    track.getAudioFormat() == AudioFormat.ENCODING_PCM_FLOAT);
        } catch (Throwable ignored) {
        }
    }

    private void fillFromBytes(AudioTrack track, ByteBuffer view, int start, int size, boolean isFloat) {
        int channels = Math.max(1, track.getChannelCount());
        int bytesPerSample = isFloat ? 4 : 2;
        int frameBytes = channels * bytesPerSample;
        int frames = Math.min(MAX_CHUNK_FRAMES, Math.min(size, view.limit() - start) / frameBytes);
        float[] out = chunk(frames);
        for (int f = 0; f < frames; f++) {
            int base = start + f * frameBytes;
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                int at = base + c * bytesPerSample;
                sum += isFloat ? view.getFloat(at) : view.getShort(at) / 32768f;
            }
            out[f] = sum / channels;
        }
        process(track, frames);
    }

    /**
     * Every write: the beat tracker runs over ALL of it (Spotify hands over large chunks, and
     * looking only at the first 1024 frames of each - as before - saw a kick only when one
     * happened to land in that sliver, so real songs barely pulsed). The FFT spectrum and
     * loudness stay at ~30 readings a second from the head of the chunk.
     */
    private void process(AudioTrack track, int frames) {
        if (frames <= 0) return;
        int sampleRate = Math.max(8000, track.getSampleRate());
        long startsAt = SystemClock.uptimeMillis() + latencyMs(track);
        // Keep the timeline monotonic: consecutive writes are consecutive audio, so a chunk that
        // the estimate places slightly before the previous one's end simply follows it.
        if (startsAt < timelineEnd && timelineEnd - startsAt < 400L) startsAt = timelineEnd;
        timelineEnd = startsAt + frames * 1000L / sampleRate;
        trackBass(chunk, frames, sampleRate, startsAt);
        long now = SystemClock.uptimeMillis();
        if (now - lastReadingMs < MIN_INTERVAL_MS) return;
        lastReadingMs = now;
        int n = Math.min(WINDOW, frames);
        System.arraycopy(chunk, 0, mono, 0, n);
        analyse(track, n);
    }

    // ---- beat tracking: bass band (~45-130 Hz) energy per ~10ms hop, spectral-flux onsets
    private float lpA1, lpA2, lpB1, lpB2;   // two 2-pole low-passes (high and low corner)
    private float hopEnergy;
    private int hopFill;
    private float prevHop1, prevHop2;
    private float fluxAverage;
    private float energyAverage;
    private float envelope;
    // Timeline of envelope values keyed by the uptime at which that audio is heard.
    private static final int RING = 512;
    private final long[] ringAt = new long[RING];
    private final float[] ringValue = new float[RING];
    private int ringHead = -1;
    private long timelineEnd;

    private void trackBass(float[] x, int frames, int sampleRate, long startsAtMs) {
        int hop = Math.max(64, sampleRate / 100); // ~10ms
        float aHigh = onePole(130f, sampleRate);
        float aLow = onePole(45f, sampleRate);
        float hopSeconds = hop / (float) sampleRate;
        float decay = (float) Math.exp(-hopSeconds / 0.16f);
        float avgRate = hopSeconds / 1.2f;   // ~1.2s running averages
        for (int i = 0; i < frames; i++) {
            float v = x[i];
            lpA1 += (v - lpA1) * aHigh;
            lpA2 += (lpA1 - lpA2) * aHigh;
            lpB1 += (v - lpB1) * aLow;
            lpB2 += (lpB1 - lpB2) * aLow;
            float band = lpA2 - lpB2;
            hopEnergy += band * band;
            if (++hopFill < hop) continue;
            float e = hopEnergy / hop;
            hopEnergy = 0f;
            hopFill = 0;
            // Flux: how much the bass rose against the lower of the last two hops, so a kick's
            // ~10-20ms attack registers even on a mix whose bassline never lets the level drop.
            float rise = Math.max(0f, e - Math.min(prevHop1, prevHop2));
            prevHop2 = prevHop1;
            prevHop1 = e;
            energyAverage = energyAverage == 0f ? e : energyAverage + (e - energyAverage) * avgRate;
            fluxAverage = fluxAverage + (rise - fluxAverage) * avgRate;
            float pulse = 0f;
            if (energyAverage > 1e-7f) {
                // Relative to both the song's typical rise and its bass level: quiet and loud
                // masters pulse alike, and a near-silent passage cannot trigger on noise.
                float norm = Math.max(fluxAverage * 2.2f, energyAverage * 0.18f);
                pulse = Math.max(0f, Math.min(1f, (rise - norm) / (norm * 1.6f)));
            }
            envelope = Math.max(pulse, envelope * decay);
            int offsetMs = Math.round(1000f * (i + 1) / sampleRate);
            synchronized (ringAt) {
                ringHead = (ringHead + 1) % RING;
                ringAt[ringHead] = startsAtMs + offsetMs;
                ringValue[ringHead] = envelope;
            }
        }
    }

    private static float onePole(float cutoffHz, int sampleRate) {
        return (float) (1.0 - Math.exp(-2.0 * Math.PI * cutoffHz / sampleRate));
    }

    /** Beat envelope (0..1) for the audio being heard right now. Any thread. */
    float beatNow() {
        if (!listeningEnabled && !AudioDebug.watching()) return 0f;
        long now = SystemClock.uptimeMillis();
        synchronized (ringAt) {
            if (ringHead < 0) return 0f;
            for (int k = 0, i = ringHead; k < RING; k++, i = (i - 1 + RING) % RING) {
                long at = ringAt[i];
                if (at == 0L) return 0f;
                if (at <= now) {
                    // Stale timeline (playback stopped): let it fall silent.
                    return now - at > 250L ? 0f : ringValue[i];
                }
            }
        }
        return 0f;
    }

    private static long latencyMs(AudioTrack track) {
        try {
            int rate = track.getSampleRate();
            if (rate > 0) return Math.min(MAX_ALIGN_DELAY_MS, track.getBufferSizeInFrames() * 1000L / rate);
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private void analyse(AudioTrack track, int frames) {
        if (frames < 64) return;
        int sampleRate = Math.max(8000, track.getSampleRate());
        if (AudioDebug.watching()) {
            int encoding = track.getAudioFormat();
            AudioDebug.format = (encoding == AudioFormat.ENCODING_PCM_FLOAT ? "PCM float"
                    : encoding == AudioFormat.ENCODING_PCM_8BIT ? "PCM 8-bit" : "PCM 16-bit")
                    + " · " + sampleRate + " Hz · " + track.getChannelCount() + " ch";
        }
        double sumSquares = 0;
        for (int i = 0; i < frames; i++) sumSquares += mono[i] * mono[i];
        float loudness = Math.min(1f, (float) Math.sqrt(sumSquares / frames) * 2.7f);

        // Windowed FFT; a short write is zero-padded.
        for (int i = 0; i < WINDOW; i++) {
            re[i] = i < frames ? mono[i] * hann[i] : 0f;
            im[i] = 0f;
        }
        fft(re, im);
        float binHz = sampleRate / (float) WINDOW;

        float[] spectrum = new float[BANDS];
        double logMin = Math.log(MIN_HZ);
        double logSpan = Math.log(Math.min(MAX_HZ, sampleRate / 2f)) - logMin;
        for (int b = 0; b < BANDS; b++) {
            int from = Math.max(1, (int) (Math.exp(logMin + logSpan * b / BANDS) / binHz));
            int to = Math.max(from + 1, (int) (Math.exp(logMin + logSpan * (b + 1) / BANDS) / binHz));
            float energy = 0f;
            for (int k = from; k < to && k < WINDOW / 2; k++) energy += re[k] * re[k] + im[k] * im[k];
            float magnitude = (float) Math.sqrt(energy / (to - from));
            // Auto-gain per band: its recent peak decays slowly, so a quiet passage still fills
            // the bars and a loud one does not pin them at the top.
            bandPeak[b] = Math.max(magnitude, bandPeak[b] * 0.995f);
            spectrum[b] = bandPeak[b] <= 1e-6f ? 0f
                    : (float) Math.pow(Math.min(1f, magnitude / bandPeak[b]), 1.4);
        }
        deliver(track, loudness, spectrum);
    }

    /** In-place iterative radix-2 FFT; {@code re.length} must be a power of two. */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            float wr = (float) Math.cos(angle);
            float wi = (float) Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float cr = 1f;
                float ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    float tr = re[b] * cr - im[b] * ci;
                    float ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    private void deliver(AudioTrack track, float loudness, float[] spectrum) {
        long delay = latencyMs(track);
        main.postDelayed(() -> {
            float beatNow = beatNow();
            AudioDebug.loudness = loudness;
            AudioDebug.beat = beatNow;
            AudioDebug.spectrum = spectrum;
            if (listeningEnabled) listener.onAnalysis(loudness, beatNow, spectrum);
        }, delay);
    }
}
