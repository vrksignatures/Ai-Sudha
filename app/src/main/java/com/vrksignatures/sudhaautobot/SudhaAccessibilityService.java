package com.vrksignatures.sudhaautobot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.vrksignatures.sudhaautobot.core.AccessibilityTree;
import com.vrksignatures.sudhaautobot.core.BotState;
import com.vrksignatures.sudhaautobot.core.DailyLedger;
import com.vrksignatures.sudhaautobot.core.DateRollbackPolicy;
import com.vrksignatures.sudhaautobot.core.WorkflowStateMachine;
import com.vrksignatures.sudhaautobot.ui.FloatingPanelController;

/**
 * The only component that talks to the Android accessibility framework.
 *
 * <p>Responsibilities, and nothing more:</p>
 * <ol>
 *   <li>own the singleton {@link WorkflowStateMachine} and {@link DailyLedger},</li>
 *   <li>translate accessibility events into {@code machine.tick()} calls
 *       (debounced), with a watchdog so a silent screen cannot stall the run,</li>
 *   <li>keep the {@link FloatingPanelController} in sync with the ledger,</li>
 *   <li>stop cleanly when the operator disables the service or the screen turns
 *       off.</li>
 * </ol>
 *
 * <p>All engine work happens on the main thread: the accessibility API must be
 * called from it, and the engine is throttled to one action every
 * {@link WorkflowStateMachine#ACTION_GAP_MS} ms, so a background executor would
 * add risk without buying speed.</p>
 */
public class SudhaAccessibilityService extends AccessibilityService {

    private static final String TAG = "SudhaBot";
    public static final String PREFS = "sudha_bot";
    public static final String KEY_TARGET_PACKAGE = "target_package";

    /** Broadcasts used by MainActivity and the panel. */
    public static final String ACTION_STATE = "com.vrksignatures.sudhaautobot.STATE";
    public static final String ACTION_START = "com.vrksignatures.sudhaautobot.START";
    public static final String ACTION_PAUSE = "com.vrksignatures.sudhaautobot.PAUSE";
    public static final String ACTION_RESET = "com.vrksignatures.sudhaautobot.RESET";

    /** Watchdog period: keeps the bot alive on screens that emit no events. */
    private static final long WATCHDOG_MS = 700L;
    /** Panel refresh period. */
    private static final long PANEL_REFRESH_MS = 500L;
    /** Debounce for content-change storms (the retailer list fires hundreds). */
    private static final long EVENT_DEBOUNCE_MS = 120L;

    private static SudhaAccessibilityService instance;

    private Handler handler;
    private AccessibilityTree tree;
    private DailyLedger ledger;
    private DateRollbackPolicy datePolicy;
    private WorkflowStateMachine machine;
    private FloatingPanelController panel;

    private String targetPackage;
    private long lastTickAt;
    private boolean screenOff;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        targetPackage = prefs.getString(KEY_TARGET_PACKAGE, BuildConfig.DEFAULT_TARGET_PACKAGE);

        tree = new AccessibilityTree(this);
        ledger = DailyLedger.open(this::getFilesDir);
        datePolicy = new DateRollbackPolicy();
        panel = new FloatingPanelController(this, this::handlePanelCommand);

        machine = new WorkflowStateMachine(this, tree, ledger, datePolicy,
                (state, message) -> {
                    panel.render(machineSnapshot());
                    broadcastState();
                });
        machine.setTargetPackage(targetPackage);

        registerScreenReceiver();
        handler.post(watchdog);
        Log.i(TAG, "service created · target=" + targetPackage);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || screenOff) {
            return;
        }
        if (!machine.wantsTick()) {
            return;
        }
        if (!isRelevantEvent(event)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTickAt < EVENT_DEBOUNCE_MS) {
            return;
        }
        lastTickAt = now;
        machine.tick();
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "service interrupted");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "service unbound · stopping engine");
        stopEverything();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopEverything();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        try {
            unregisterReceiver(screenReceiver);
        } catch (RuntimeException ignored) {
            // never registered
        }
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * Restricts the service to the configured package so the bot is not woken
     * by unrelated apps. MainActivity calls this after the operator changes the
     * target.
     */
    public void applyTargetPackage(String pkg) {
        this.targetPackage = pkg == null ? "" : pkg.trim();
        if (machine != null) {
            machine.setTargetPackage(this.targetPackage);
        }
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.packageNames = this.targetPackage.isEmpty()
                        ? null
                        : new String[]{this.targetPackage};
                setServiceInfo(info);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not apply package filter", e);
        }
        broadcastState();
    }

    public String getTargetPackage() {
        return targetPackage;
    }

    // ------------------------------------------------------------------
    // Panel commands
    // ------------------------------------------------------------------

    private void handlePanelCommand(FloatingPanelController.Command command) {
        switch (command) {
            case START:
                machine.start();
                break;
            case PAUSE:
                machine.pause();
                break;
            case TOGGLE:
                machine.togglePause();
                break;
            case RESET:
                machine.resetLedgerAndStop();
                break;
            case CLOSE:
                panel.hide();
                machine.resetEngine();
                break;
            default:
                break;
        }
        panel.render(machineSnapshot());
        broadcastState();
    }

    /** Panel needs a cheap, immutable view of the world. */
    public FloatingPanelController.Snapshot machineSnapshot() {
        if (machine == null) {
            return FloatingPanelController.Snapshot.idle();
        }
        return new FloatingPanelController.Snapshot(
                ledger.countSuccess(),
                ledger.countSkipped(),
                ledger.countErrors(),
                machine.lastVisibleRetailers(),
                com.vrksignatures.sudhaautobot.core.RetailerPolicy.EXPECTED_TOTAL,
                machine.getCurrentSmr(),
                machine.getState(),
                messageSafely(),
                machine.isRunning(),
                machine.isPaused());
    }

    private String messageSafely() {
        try {
            return machine.getMessage();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Panel visibility
    // ------------------------------------------------------------------

    public void showPanel() {
        if (panel == null) {
            return;
        }
        if (panel.show()) {
            panel.render(machineSnapshot());
            startPanelRefresh();   // counters/state refresh while it is on screen
        }
    }

    public void hidePanel() {
        if (panel != null) {
            panel.hide();
        }
        stopPanelRefresh();
    }

    public boolean isPanelShowing() {
        return panel != null && panel.isShowing();
    }

    public static SudhaAccessibilityService get() {
        return instance;
    }

    public DailyLedger getLedger() {
        return ledger;
    }

    public WorkflowStateMachine getMachine() {
        return machine;
    }

    // ------------------------------------------------------------------
    // Tick loop
    // ------------------------------------------------------------------

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                if (!screenOff && machine != null && machine.wantsTick()) {
                    machine.tick();
                }
                if (ledger != null && ledger.rolloverIfNeeded()) {
                    datePolicy = new DateRollbackPolicy();
                    Log.i(TAG, "clock rolled to a new day");
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "watchdog tick failed", e);
            } finally {
                handler.postDelayed(this, WATCHDOG_MS);
            }
        }
    };

    private final Runnable panelRefresh = new Runnable() {
        @Override
        public void run() {
            if (panel != null && panel.isShowing()) {
                panel.render(machineSnapshot());
            }
            handler.postDelayed(this, PANEL_REFRESH_MS);
        }
    };

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Only these events can change what the bot should do. ANNOUNCEMENT and
     * TEXT_TRAVERSAL in particular must be ignored, they fire endlessly while a
     * dialog animates.
     */
    private boolean isRelevantEvent(AccessibilityEvent event) {
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                return true;
            default:
                return false;
        }
    }

    private void broadcastState() {
        try {
            Intent intent = new Intent(ACTION_STATE);
            intent.putExtra("state", machine == null ? "IDLE" : machine.getState().name());
            intent.putExtra("message", messageSafely());
            intent.putExtra("success", ledger == null ? 0 : ledger.countSuccess());
            intent.putExtra("skipped", ledger == null ? 0 : ledger.countSkipped());
            intent.putExtra("errors", ledger == null ? 0 : ledger.countErrors());
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (RuntimeException ignored) {
            // state broadcast is best effort
        }
    }

    private void stopEverything() {
        hidePanel();
        if (machine != null) {
            machine.resetEngine();
        }
        if (ledger != null) {
            ledger.flush();
        }
    }

    // ------------------------------------------------------------------
    // Screen on/off: pause the moment the operator pockets the phone
    // ------------------------------------------------------------------

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOff = true;
                if (machine != null && machine.isRunning() && !machine.isPaused()) {
                    machine.pause();
                    Log.i(TAG, "screen off · paused");
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOff = false;
            }
        }
    };

    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    /** Starts the panel refresh loop (called when the panel becomes visible). */
    private void startPanelRefresh() {
        handler.removeCallbacks(panelRefresh);
        handler.post(panelRefresh);
    }

    /** Stops the refresh loop so a hidden panel costs nothing. */
    private void stopPanelRefresh() {
        handler.removeCallbacks(panelRefresh);
    }
}
