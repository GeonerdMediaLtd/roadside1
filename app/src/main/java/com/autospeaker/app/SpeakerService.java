package com.autospeaker.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;

/**
 * Watches the phone's call state. When a call connects it turns the
 * loudspeaker on in two ways:
 *   1. through the audio system (AudioManager), which works on many phones;
 *   2. if that didn't take, by pressing the "Speaker" button on the call
 *      screen, which works on phones where (1) is blocked.
 * It acts once per call, so if you turn the speaker off yourself it stays off.
 */
public class SpeakerService extends AccessibilityService {

    private static final String TAG = "AutoSpeaker";
    private static final int MAX_ATTEMPTS = 10;     // ~7 seconds of retries
    private static final long RETRY_MS = 700;

    /** Words used for the Speaker button in common dialers / languages. */
    private static final String[] SPEAKER_WORDS = {
            "speaker", "loudspeaker", "speakerphone",
            "स्पीकर",      // Hindi / Marathi
            "স্পিকার",     // Bengali
            "ஸ்பீக்கர்",   // Tamil
            "స్పీకర్",     // Telugu
            "ಸ್ಪೀಕರ್",     // Kannada
            "സ്പീക്കർ",    // Malayalam
            "સ્પીકર",      // Gujarati
            "ਸਪੀਕਰ",      // Punjabi
            "altavoz", "haut-parleur", "lautsprecher", "alto-falante", "viva voz"
    };

    private static SpeakerService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TelephonyManager telephony;
    private AudioManager audio;
    private Object telephonyCallback;       // TelephonyCallback on Android 12+
    private PhoneStateListener legacyListener;

    private int lastState = TelephonyManager.CALL_STATE_IDLE;
    private boolean wasRinging = false;
    private boolean working = false;        // trying to enable speaker for this call
    private boolean usedAudioApi = false;
    private int attempt = 0;

    private final Runnable attemptRunnable = this::tryEnableSpeaker;

    // ---------------------------------------------------------------- lifecycle

    static boolean isRunning() {
        return instance != null;
    }

    /** Called by the settings screen after the phone permission is granted. */
    static void refresh() {
        if (instance != null) instance.registerCallListener();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        telephony = getSystemService(TelephonyManager.class);
        audio = getSystemService(AudioManager.class);
        registerCallListener();
        Log.i(TAG, "Service connected");
    }

    @Override
    public void onDestroy() {
        unregisterCallListener();
        handler.removeCallbacksAndMessages(null);
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Nothing to do: the call-state listener drives everything.
    }

    @Override
    public void onInterrupt() { }

    // ---------------------------------------------------------- call listening

    private void registerCallListener() {
        if (telephony == null || telephonyCallback != null || legacyListener != null) return;
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE not granted yet");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                CallStateCallback cb = new CallStateCallback();
                telephony.registerTelephonyCallback(getMainExecutor(), cb);
                telephonyCallback = cb;
            } else {
                legacyListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        onCallState(state);
                    }
                };
                telephony.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
            Log.i(TAG, "Listening for calls");
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot listen to call state", e);
        }
    }

    private void unregisterCallListener() {
        if (telephony == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback != null) {
            telephony.unregisterTelephonyCallback((TelephonyCallback) telephonyCallback);
        }
        if (legacyListener != null) {
            telephony.listen(legacyListener, PhoneStateListener.LISTEN_NONE);
        }
        telephonyCallback = null;
        legacyListener = null;
    }

    private final class CallStateCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            onCallState(state);
        }
    }

    private void onCallState(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                wasRinging = true;
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (lastState != TelephonyManager.CALL_STATE_OFFHOOK) {
                    onCallStarted(wasRinging);
                }
                break;
            case TelephonyManager.CALL_STATE_IDLE:
                onCallEnded();
                wasRinging = false;
                break;
        }
        lastState = state;
    }

    private void onCallStarted(boolean incoming) {
        Prefs p = new Prefs(this);
        if (!p.enabled()) return;
        if (incoming && !p.incoming()) return;
        if (!incoming && !p.outgoing()) return;
        if (p.skipHeadset() && headsetConnected()) {
            Log.i(TAG, "Headset/Bluetooth connected, leaving audio alone");
            return;
        }
        Log.i(TAG, (incoming ? "Incoming" : "Outgoing") + " call connected");
        working = true;
        attempt = 0;
        handler.removeCallbacks(attemptRunnable);
        handler.postDelayed(attemptRunnable, p.delaySec() * 1000L);
    }

    private void onCallEnded() {
        working = false;
        handler.removeCallbacks(attemptRunnable);
        if (usedAudioApi && audio != null) {
            // Put the audio system back the way it was so the next call /
            // media playback isn't affected.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audio.clearCommunicationDevice();
                }
                audio.setSpeakerphoneOn(false);
            } catch (Exception ignored) { }
        }
        usedAudioApi = false;
    }

    // ------------------------------------------------------- turning speaker on

    private void tryEnableSpeaker() {
        if (!working) return;
        attempt++;

        // Way 1: ask the audio system directly.
        if (attempt == 1) {
            if (isSpeakerOn()) {
                done("speaker already on");
                return;
            }
            enableViaAudioApi();
        } else {
            // Way 2: the call screen's own Speaker button is the most reliable
            // indicator of what the phone is really doing, so check it first
            // and press it if it is still off.
            int result = pressSpeakerButton();
            if (result == BUTTON_PRESSED || result == BUTTON_ALREADY_ON) {
                done(result == BUTTON_PRESSED ? "pressed Speaker button" : "button already on");
                return;
            }
            // Call screen not visible (e.g. user is on the home screen):
            // fall back to what the audio system reports.
            if (attempt >= 4 && isSpeakerOn()) {
                done("audio system reports speaker on");
                return;
            }
        }

        if (attempt < MAX_ATTEMPTS) {
            handler.postDelayed(attemptRunnable, RETRY_MS);
        } else {
            done("gave up");
        }
    }

    private void done(String why) {
        Log.i(TAG, "Done for this call: " + why);
        working = false;
        handler.removeCallbacks(attemptRunnable);
    }

    private void enableViaAudioApi() {
        if (audio == null) return;
        usedAudioApi = true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (AudioDeviceInfo d : audio.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        audio.setCommunicationDevice(d);
                        break;
                    }
                }
            }
            audio.setSpeakerphoneOn(true);
        } catch (Exception e) {
            Log.w(TAG, "Audio API refused", e);
        }
    }

    private boolean isSpeakerOn() {
        if (audio == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo d = audio.getCommunicationDevice();
                if (d != null && d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return true;
            }
            return audio.isSpeakerphoneOn();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean headsetConnected() {
        if (audio == null) return false;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------ pressing the call button

    private static final int BUTTON_NOT_FOUND = 0;
    private static final int BUTTON_PRESSED = 1;
    private static final int BUTTON_ALREADY_ON = 2;

    private int pressSpeakerButton() {
        String defaultDialer = null;
        try {
            TelecomManager tm = getSystemService(TelecomManager.class);
            if (tm != null) defaultDialer = tm.getDefaultDialerPackage();
        } catch (Exception ignored) { }

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                int r = pressIn(w.getRoot(), defaultDialer);
                if (r != BUTTON_NOT_FOUND) return r;
            }
        } catch (Exception e) {
            Log.w(TAG, "Window scan failed", e);
        }
        return pressIn(getRootInActiveWindow(), defaultDialer);
    }

    private int pressIn(AccessibilityNodeInfo root, String defaultDialer) {
        if (root == null || root.getPackageName() == null) return BUTTON_NOT_FOUND;
        if (!isCallScreenPackage(root.getPackageName().toString(), defaultDialer)) {
            return BUTTON_NOT_FOUND;
        }
        AccessibilityNodeInfo button = findSpeaker(root, 0);
        if (button == null) return BUTTON_NOT_FOUND;

        AccessibilityNodeInfo target = button;
        while (target != null && !target.isClickable()) target = target.getParent();
        if (target == null) return BUTTON_NOT_FOUND;

        if (looksOn(button) || looksOn(target)) return BUTTON_ALREADY_ON;
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ? BUTTON_PRESSED : BUTTON_NOT_FOUND;
    }

    private static boolean isCallScreenPackage(String pkg, String defaultDialer) {
        if (pkg.equals(defaultDialer)) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("incallui") || p.contains("dialer") || p.contains("telecom")
                || p.contains(".phone") || p.contains("contacts") || p.contains("truecaller");
    }

    private AccessibilityNodeInfo findSpeaker(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return null;
        if (looksLikeSpeaker(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findSpeaker(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean looksLikeSpeaker(AccessibilityNodeInfo n) {
        String id = lower(n.getViewIdResourceName());
        String label = lower(n.getText()) + " " + lower(n.getContentDescription());
        if (label.contains("turn off") || label.contains("earpiece") || label.contains("handset")) {
            return false;
        }
        if (id.contains("speaker")) return true;
        for (String w : SPEAKER_WORDS) {
            if (label.contains(w)) return true;
        }
        return false;
    }

    /** True if the button already shows the speaker as on. */
    private static boolean looksOn(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if ((n.isCheckable() && n.isChecked()) || n.isSelected()) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            String state = lower(n.getStateDescription());
            if (state.equals("on") || state.contains("selected") && !state.contains("not")) {
                return true;
            }
        }
        String label = lower(n.getText()) + " " + lower(n.getContentDescription());
        return label.contains("speaker on") || label.contains("speaker, on")
                || label.contains("speaker is on") || label.contains("speaker: on");
    }

    private static String lower(CharSequence s) {
        return s == null ? "" : s.toString().toLowerCase(Locale.ROOT);
    }
}
