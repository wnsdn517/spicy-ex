package com.eza.spicyex.hooks;

import android.os.Handler;
import android.os.Looper;

import com.eza.spicyex.xposed.XpLog;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Once the embedded web player is running it registers itself with Spotify's own backend as a
 * real Connect device (Spotify does this automatically for any authenticated open.spotify.com
 * session - no local discovery/Zeroconf work needed on our side), but the user still has to open
 * the Connect picker and select it by hand every time Spotify (re)starts. This transfers playback
 * to it once, shortly after startup, the same way manually picking it from the device list would.
 * Runs at most once per process lifetime; a manual switch to another device afterward is left
 * alone (this never re-fires later, e.g. from a periodic check).
 */
final class ConnectAutoTransfer {
    private static final String TAG = "[SpicyConnectAuto]";
    private static final String DEVICES_URL = "https://api.spotify.com/v1/me/player/devices";
    private static final String TRANSFER_URL = "https://api.spotify.com/v1/me/player";
    private static volatile boolean attempted;

    private ConnectAutoTransfer() {
    }

    /** Fire-and-forget; safe to call from the main thread. Delayed so the web player has had a
     *  realistic chance to finish loading and register itself as a device with Spotify first. */
    static void scheduleOnce(long delayMs) {
        if (attempted) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (attempted) return;
            attempted = true;
            NativeRuntime.LYRICS_IO.submit(ConnectAutoTransfer::run);
        }, delayMs);
    }

    private static void run() {
        try {
            SpotifyTokenState.Authorized auth = SpotifyTokenStore.authorization(System.currentTimeMillis());
            if (auth == null) {
                XpLog.log(TAG + " no usable token, skipping");
                return;
            }
            String deviceId = findWebPlayerDeviceId(auth.token());
            if (deviceId == null) {
                XpLog.log(TAG + " no web player device found yet");
                return;
            }
            transferTo(auth.token(), deviceId);
        } catch (Throwable t) {
            XpLog.log(TAG + " failed type=" + t.getClass().getName() + " msg=" + t.getMessage());
        }
    }

    private static String findWebPlayerDeviceId(String token) throws Exception {
        Request request = new Request.Builder()
                .url(DEVICES_URL)
                .header("Authorization", "Bearer " + token)
                .get()
                .build();
        try (Response response = NativeRuntime.HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                XpLog.log(TAG + " devices lookup http=" + response.code());
                return null;
            }
            JSONObject body = new JSONObject(response.body().string());
            JSONArray devices = body.optJSONArray("devices");
            if (devices == null) return null;
            for (int i = 0; i < devices.length(); i++) {
                JSONObject device = devices.optJSONObject(i);
                if (device == null) continue;
                String name = device.optString("name", "");
                String type = device.optString("type", "");
                // The embedded player identifies as a desktop Chrome browser (see WebPlayerService's
                // browser-fingerprint spoof); Spotify names Web Playback SDK sessions accordingly.
                boolean looksLikeOurWebPlayer = "Computer".equalsIgnoreCase(type)
                        && (name.toLowerCase(java.util.Locale.ROOT).contains("web player")
                                || name.toLowerCase(java.util.Locale.ROOT).contains("chrome"));
                if (looksLikeOurWebPlayer) return device.optString("id", null);
            }
            return null;
        }
    }

    private static void transferTo(String token, String deviceId) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("device_ids", new JSONArray().put(deviceId));
        // Preserve whatever the phone was already doing (playing or paused) instead of forcing
        // playback to start as part of a silent, automatic startup transfer.
        payload.put("play", false);
        Request request = new Request.Builder()
                .url(TRANSFER_URL)
                .header("Authorization", "Bearer " + token)
                .put(okhttp3.RequestBody.create(payload.toString(),
                        okhttp3.MediaType.get("application/json")))
                .build();
        try (Response response = NativeRuntime.HTTP.newCall(request).execute()) {
            XpLog.log(TAG + " transfer http=" + response.code());
        }
    }
}
