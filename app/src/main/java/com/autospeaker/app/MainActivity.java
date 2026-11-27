package com.autospeaker.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings / setup screen. */
public class MainActivity extends Activity {

    private static final int REQ_PHONE = 1;

    private Prefs prefs;
    private TextView status, delayLabel;
    private Button btnPhone, btnAccess, btnBattery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        status = findViewById(R.id.status);
        btnPhone = findViewById(R.id.btnPhone);
        btnAccess = findViewById(R.id.btnAccess);
        btnBattery = findViewById(R.id.btnBattery);
        delayLabel = findViewById(R.id.delayLabel);

        btnPhone.setOnClickListener(v ->
                requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQ_PHONE));

        btnAccess.setOnClickListener(v -> {
            Toast.makeText(this, "Find \"Auto Speaker\" and turn it on", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        findViewById(R.id.btnAppInfo).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));

        btnBattery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });

        bindSwitch(R.id.swEnabled, Prefs.ENABLED, prefs.enabled());
        bindSwitch(R.id.swIncoming, Prefs.INCOMING, prefs.incoming());
        bindSwitch(R.id.swOutgoing, Prefs.OUTGOING, prefs.outgoing());
        bindSwitch(R.id.swHeadset, Prefs.SKIP_HEADSET, prefs.skipHeadset());

        SeekBar delay = findViewById(R.id.delay);
        delay.setProgress(prefs.delaySec());
        showDelay(prefs.delaySec());
        delay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                prefs.put(Prefs.DELAY_SEC, value);
                showDelay(value);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PHONE) {
            SpeakerService.refresh();
            if (!hasPhonePermission() && !shouldShowRequestPermissionRationale(
                    Manifest.permission.READ_PHONE_STATE)) {
                // Permanently denied: send the user to App info > Permissions.
                Toast.makeText(this, "Open Permissions → Phone → Allow",
                        Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
            refreshUi();
        }
    }

    private void bindSwitch(int id, String key, boolean value) {
        Switch sw = findViewById(id);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            prefs.put(key, checked);
            refreshUi();
        });
    }

    private void showDelay(int sec) {
        delayLabel.setText(sec == 0
                ? "Turn speaker on: as soon as the call connects"
                : "Turn speaker on: " + sec + " second" + (sec == 1 ? "" : "s") + " after the call connects");
    }

    private void refreshUi() {
        boolean phone = hasPhonePermission();
        boolean access = accessibilityEnabled();
        boolean battery = batteryUnrestricted();

        markDone(btnPhone, phone, "1. Allow call detection");
        markDone(btnAccess, access, "2. Turn on Auto Speaker in Accessibility");
        markDone(btnBattery, battery, "3. Stop battery saver from closing it (recommended)");

        if (phone && access && prefs.enabled()) {
            status.setBackgroundColor(0xFFE3F4EC);
            status.setText("✅  Ready. The speaker will turn on by itself when a call connects.");
        } else if (!prefs.enabled()) {
            status.setBackgroundColor(0xFFEEEEEE);
            status.setText("⏸  Paused. Switch \"Auto speaker on\" back on to use it.");
        } else {
            status.setBackgroundColor(0xFFFFF4E5);
            String todo = !phone && !access ? "steps 1 and 2" : !phone ? "step 1" : "step 2";
            status.setText("⚠️  Almost there. Finish " + todo + " below.");
        }
    }

    private static void markDone(Button b, boolean done, String label) {
        b.setText(done ? "✓  " + label.substring(3) : label);
        b.setEnabled(!done);
    }

    private boolean hasPhonePermission() {
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryUnrestricted() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean accessibilityEnabled() {
        if (SpeakerService.isRunning()) return true;
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String me = new ComponentName(this, SpeakerService.class).flattenToString();
        String meShort = new ComponentName(this, SpeakerService.class).flattenToShortString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        }
        return false;
    }
}
