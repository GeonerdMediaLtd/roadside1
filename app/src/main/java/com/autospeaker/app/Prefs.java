package com.autospeaker.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Small wrapper around the app's settings. */
final class Prefs {
    static final String ENABLED = "enabled";
    static final String INCOMING = "incoming";
    static final String OUTGOING = "outgoing";
    static final String SKIP_HEADSET = "skip_headset";
    static final String DELAY_SEC = "delay_sec";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    boolean enabled()     { return sp.getBoolean(ENABLED, true); }
    boolean incoming()    { return sp.getBoolean(INCOMING, true); }
    boolean outgoing()    { return sp.getBoolean(OUTGOING, true); }
    boolean skipHeadset() { return sp.getBoolean(SKIP_HEADSET, true); }
    int delaySec()        { return sp.getInt(DELAY_SEC, 1); }

    void put(String key, boolean value) { sp.edit().putBoolean(key, value).apply(); }
    void put(String key, int value)     { sp.edit().putInt(key, value).apply(); }
}
