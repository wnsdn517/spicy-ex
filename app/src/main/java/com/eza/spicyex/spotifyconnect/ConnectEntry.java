package com.eza.spicyex.spotifyconnect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.ResultReceiver;

/**
 * Small, stable boundary between code injected into Spotify and the player owned by SpicyEX.
 *
 * <p>Injected code has Spotify's UID, so it must not try to construct or retain player objects.
 * Commands go through our exported receiver; it runs under SpicyEX's UID and starts the
 * foreground service locally. Keeping this class free of player state is intentional: it is
 * loaded reflectively by the common (lite/full) source set.
 */
public final class ConnectEntry {
    private static final String PACKAGE = "com.eza.spicyex";
    private static final String RECEIVER = "com.eza.spicyex.player.PlayerWarmReceiver";
    private static final String LOGIN_ACTIVITY = "com.eza.spicyex.player.WebLoginActivity";
    private static final String EXTRA_WARM_REPLY = "warm_reply";

    private ConnectEntry() {
    }

    public static void init(Context context) {
        // Deliberately stateless. Kept for the reflection ABI used by SpotifyConnectHook.
    }

    public static void setEnabled(Context context, boolean enabled) {
        // Turning the setting on must actually start the player - warmUp() is what really
        // launches WebPlayerService; without this, flipping the switch did nothing observable
        // and the only ways to ever start it were the settings "Play test track" row or hitting
        // the injected picker button inside an already-open Spotify Connect dialog.
        if (enabled) warmUp(context, null); else send(context, "com.eza.spicyex.player.STOP", null);
    }

    public static void warmUp(Context context, ResultReceiver reply) {
        Intent command = new Intent("com.eza.spicyex.player.WARMUP");
        if (reply != null) command.putExtra(EXTRA_WARM_REPLY, reply);
        send(context, command);
    }

    public static void openLogin(Context context) {
        if (context == null) return;
        // This method is called from a user tap in Spotify. Starting the exported activity here
        // preserves that user-initiated launch exemption; routing it through a background service
        // can be rejected on Android 10+ before the login screen is ever shown.
        try {
            Intent login = new Intent();
            login.setComponent(new ComponentName(PACKAGE, LOGIN_ACTIVITY));
            login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(login);
            return;
        } catch (Throwable ignored) {
            // The receiver path is useful on older devices and as a fallback for OEM policies.
        }
        send(context, "com.eza.spicyex.player.LOGIN", null);
    }

    public static void playTestTrack(Context context) {
        Intent command = new Intent("com.eza.spicyex.player.OPEN");
        command.putExtra("uri", "spotify:track:11dFghVXANMlKmJXsNCbNl");
        send(context, command);
    }

    // Compatibility endpoints used by ConnectMirror. They intentionally do not infer remote
    // commands from Spotify traffic: doing so can create playback loops or duplicate requests.
    private static volatile boolean remoteActive;
    private static volatile long remoteAt;
    private static volatile boolean phonePlaying;
    private static volatile long phonePosition;

    public static void onPhoneState(Context context, boolean playing, long positionMs) {
        phonePlaying = playing;
        phonePosition = Math.max(0, positionMs);
    }

    public static void onRemoteCommand(Context context, String kind) {
        remoteActive = true;
        remoteAt = System.currentTimeMillis();
    }

    public static boolean isRemoteActive() {
        return remoteActive && System.currentTimeMillis() - remoteAt < 10L * 60_000L;
    }

    public static boolean phonePlaying() {
        return phonePlaying;
    }

    public static long phonePosition() {
        return phonePosition;
    }

    private static void send(Context context, String action, Intent extras) {
        Intent command = extras == null ? new Intent(action) : extras;
        command.setAction(action);
        send(context, command);
    }

    private static void send(Context context, Intent command) {
        if (context == null || command == null) return;
        try {
            command.setComponent(new ComponentName(PACKAGE, RECEIVER));
            context.sendBroadcast(command);
        } catch (Throwable ignored) {
        }
    }
}
