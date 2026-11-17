package com.vrksignatures.sudhaautobot;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.vrksignatures.sudhaautobot.core.DailyLedger;

/**
 * Operator console: three permissions, one package name, one panel toggle.
 *
 * <p>Nothing here is clever on purpose — this screen has to be operable by
 * someone standing behind a shop counter, on a skinned Oppo/Vivo ROM, with a
 * 3-year-old security patch level.</p>
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 4101;

    private EditText inputPackage;
    private TextView statusText;
    private TextView subtitleText;
    private TextView versionText;
    private Button btnTogglePanel;

    private SharedPreferences prefs;
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String state = intent.getStringExtra("state");
            String message = intent.getStringExtra("message");
            int ok = intent.getIntExtra("success", 0);
            int skip = intent.getIntExtra("skipped", 0);
            int err = intent.getIntExtra("errors", 0);
            renderStatus("RUNNING · " + state + "\n" + message
                    + "\nSUCCESS " + ok + "   SKIPPED " + skip + "   ERRORS " + err);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(SudhaAccessibilityService.PREFS, Context.MODE_PRIVATE);

        inputPackage = findViewById(R.id.input_package);
        statusText = findViewById(R.id.status_text);
        subtitleText = findViewById(R.id.subtitle_text);
        versionText = findViewById(R.id.version_text);
        btnTogglePanel = findViewById(R.id.btn_toggle_panel);

        String saved = prefs.getString(SudhaAccessibilityService.KEY_TARGET_PACKAGE,
                BuildConfig.DEFAULT_TARGET_PACKAGE);
        inputPackage.setText(saved);
        versionText.setText("v" + BuildConfig.VERSION_NAME + " · build " + BuildConfig.VERSION_CODE
                + " · " + BuildConfig.BUILD_TYPE);

        wireButtons();
        renderStatus(composeStatus());
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerStateReceiver();
        renderStatus(composeStatus());
        refreshPanelButton();
        if (subtitleText != null) {
            subtitleText.setText("Target: " + prefs.getString(
                    SudhaAccessibilityService.KEY_TARGET_PACKAGE, BuildConfig.DEFAULT_TARGET_PACKAGE));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (RuntimeException ignored) {
                // nothing to do
            }
            receiverRegistered = false;
        }
    }

    // ------------------------------------------------------------------
    // UI wiring
    // ------------------------------------------------------------------

    private void wireButtons() {
        Button save = findViewById(R.id.btn_save_package);
        save.setOnClickListener(v -> saveTargetPackage());

        Button accessibility = findViewById(R.id.btn_accessibility);
        accessibility.setOnClickListener(v -> openAccessibilitySettings());

        Button overlay = findViewById(R.id.btn_overlay);
        overlay.setOnClickListener(v -> requestOverlayPermission());

        btnTogglePanel.setOnClickListener(v -> togglePanel());

        Button openTarget = findViewById(R.id.btn_open_target);
        openTarget.setOnClickListener(v -> openTargetApp());

        Button resetLedger = findViewById(R.id.btn_reset_ledger);
        resetLedger.setOnClickListener(v -> resetLedger());
    }

    private void saveTargetPackage() {
        String pkg = inputPackage == null ? "" : inputPackage.getText().toString().trim();
        if (TextUtils.isEmpty(pkg) || pkg.contains(" ") || !pkg.contains(".")) {
            toast("Enter a real package name, e.g. com.vrksignatures.sudhadairy");
            return;
        }
        prefs.edit().putString(SudhaAccessibilityService.KEY_TARGET_PACKAGE, pkg).apply();
        SudhaAccessibilityService service = SudhaAccessibilityService.get();
        if (service != null) {
            service.applyTargetPackage(pkg);
        }
        if (subtitleText != null) {
            subtitleText.setText("Target: " + pkg);
        }
        toast("Saved · " + pkg);
        renderStatus(composeStatus());
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            toast("Enable SudhaAutoBot under \"Downloaded services\"");
        } catch (RuntimeException e) {
            toast("Open Settings → Accessibility manually");
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("Overlay permission is granted at install on this Android version");
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            toast("Already allowed");
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (RuntimeException ignored) {
                toast("Open Settings → Apps → Special access → Display over other apps");
            }
        }
    }

    private void togglePanel() {
        SudhaAccessibilityService service = SudhaAccessibilityService.get();
        if (service == null) {
            toast("Enable the accessibility service first");
            openAccessibilitySettings();
            return;
        }
        if (!service.isPanelShowing() && !canDrawOverlays()) {
            requestOverlayPermission();
            return;
        }
        if (service.isPanelShowing()) {
            service.hidePanel();
            toast("Panel hidden");
        } else {
            service.showPanel();
            toast("Panel shown · drag it onto the bus app");
        }
        refreshPanelButton();
    }

    private void openTargetApp() {
        String pkg = prefs.getString(SudhaAccessibilityService.KEY_TARGET_PACKAGE,
                BuildConfig.DEFAULT_TARGET_PACKAGE);
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            // Fall back to whichever Sudha package is installed.
            launch = getPackageManager().getLaunchIntentForPackage("com.sudha.dailyorders");
        }
        if (launch == null) {
            toast("Not installed: " + pkg);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void resetLedger() {
        SudhaAccessibilityService service = SudhaAccessibilityService.get();
        if (service != null && service.getLedger() != null) {
            service.getLedger().resetToday();
        } else {
            DailyLedger.open(getFilesDir()).resetToday();
        }
        toast("Today's ledger cleared");
        renderStatus(composeStatus());
    }

    // ------------------------------------------------------------------
    // Status text
    // ------------------------------------------------------------------

    private String composeStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("ACCESSIBILITY  ").append(isAccessibilityEnabled() ? "✓ enabled" : "✗ disabled");
        sb.append('\n');
        sb.append("OVERLAY        ").append(canDrawOverlays() ? "✓ allowed" : "✗ not allowed");
        sb.append('\n');
        sb.append("TARGET         ").append(prefs.getString(
                SudhaAccessibilityService.KEY_TARGET_PACKAGE, BuildConfig.DEFAULT_TARGET_PACKAGE));
        sb.append('\n');
        SudhaAccessibilityService service = SudhaAccessibilityService.get();
        if (service == null) {
            sb.append("SERVICE        not connected");
        } else {
            sb.append("LEDGER         ")
                    .append(service.getLedger().countTouched()).append(" touched today")
                    .append(" · ok ").append(service.getLedger().countSuccess())
                    .append(" · skipped ").append(service.getLedger().countSkipped())
                    .append(" · errors ").append(service.getLedger().countErrors());
        }
        return sb.toString();
    }

    private void renderStatus(String text) {
        if (statusText != null) {
            statusText.setText(text);
        }
    }

    private void refreshPanelButton() {
        if (btnTogglePanel == null) {
            return;
        }
        SudhaAccessibilityService service = SudhaAccessibilityService.get();
        boolean showing = service != null && service.isPanelShowing();
        btnTogglePanel.setText(showing ? R.string.btn_stop_panel : R.string.btn_start_panel);
    }

    // ------------------------------------------------------------------
    // Permission probes
    // ------------------------------------------------------------------

    private boolean isAccessibilityEnabled() {
        String expected = new ComponentName(this, SudhaAccessibilityService.class)
                .flattenToString();
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        // The list is colon separated and may carry the short form too.
        for (String entry : enabled.split(":")) {
            if (entry.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private void registerStateReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(SudhaAccessibilityService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            renderStatus(composeStatus());
            if (canDrawOverlays()) {
                toast("Overlay allowed");
            }
        }
    }

    /** Called from XML via android:onClick if ever needed. */
    public void onStatusTapped(View view) {
        renderStatus(composeStatus());
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }
}
