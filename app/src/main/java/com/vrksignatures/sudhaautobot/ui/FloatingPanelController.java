package com.vrksignatures.sudhaautobot.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.vrksignatures.sudhaautobot.R;
import com.vrksignatures.sudhaautobot.core.BotState;

/**
 * The floating panel that hovers over the target app.
 *
 * <h2>Why this is written the "Oppo/Vivo safe" way</h2>
 * <ul>
 *   <li>{@code TYPE_APPLICATION_OVERLAY} on O+, {@code TYPE_PHONE} on older
 *       ROMs; both are accepted by ColorOS and Funtouch without extra flags.</li>
 *   <li>The window is {@code FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS}, so it
 *       never steals IME focus from the target app's own dialogs.</li>
 *   <li>Dragging is done with raw {@code MotionEvent} maths on the header, not
 *       with {@code startDragAndDrop}: the latter is silently cancelled by
 *       aggressive vendor touch interception.</li>
 *   <li>Every colour is a resource and every widget is framework-only, so a
 *       skinned {@code Theme.Material} cannot turn the panel transparent.</li>
 * </ul>
 *
 * <p>The controller is deliberately "dumb": it renders a {@link Snapshot} and
 * forwards button presses to the service. All bot decisions live in
 * {@code WorkflowStateMachine}.</p>
 */
public class FloatingPanelController {

    private static final String TAG = "SudhaPanel";

    /** What the panel shows. Immutable so it can be handed across threads. */
    public static final class Snapshot {
        public final int success;
        public final int skipped;
        public final int errors;
        public final int visibleRetailers;
        public final int totalRetailers;
        public final String currentSmr;
        public final BotState.State state;
        public final String message;
        public final boolean running;
        public final boolean paused;

        public Snapshot(int success, int skipped, int errors, int visibleRetailers,
                        int totalRetailers, String currentSmr, BotState.State state,
                        String message, boolean running, boolean paused) {
            this.success = success;
            this.skipped = skipped;
            this.errors = errors;
            this.visibleRetailers = visibleRetailers;
            this.totalRetailers = totalRetailers <= 0 ? 125 : totalRetailers;
            this.currentSmr = currentSmr;
            this.state = state == null ? BotState.State.IDLE : state;
            this.message = message == null ? "" : message;
            this.running = running;
            this.paused = paused;
        }

        public static Snapshot idle() {
            return new Snapshot(0, 0, 0, 0, 125, null, BotState.State.IDLE, "Idle", false, false);
        }
    }

    /** What the operator pressed. */
    public enum Command {
        START, PAUSE, TOGGLE, RESET, CLOSE
    }

    private final Context context;
    private final CommandListener listener;
    private final WindowManager windowManager;

    private View rootView;
    private WindowManager.LayoutParams params;
    private boolean showing;

    private TextView titleText;
    private TextView stateText;
    private TextView countSuccess;
    private TextView countSkipped;
    private TextView countErrors;
    private TextView retailerText;
    private TextView messageText;
    private TextView btnStart;
    private TextView btnPause;
    private TextView btnReset;
    private TextView btnCollapse;
    private TextView btnClose;
    private TextView btnPassword;
    private View statusDot;
    private View bodyContainer;

    private boolean collapsed;
    private float touchDownX;
    private float touchDownY;
    private int windowDownX;
    private int windowDownY;
    private boolean dragging;

    /** Receives button presses; the service decides what they mean. */
    public interface CommandListener {
        void onCommand(Command command);
    }

    public FloatingPanelController(Context context, CommandListener listener) {
        this.context = context;
        this.listener = listener;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    public boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    public boolean isShowing() {
        return showing;
    }

    /**
     * Inflates and attaches the panel.
     *
     * @return true when the panel is on screen. Failure is silent (logged) and
     *         recoverable: the accessibility service keeps working headless.
     */
    @SuppressLint("ClickableViewAccessibility")
    public boolean show() {
        if (showing) {
            return true;
        }
        if (!canDrawOverlays()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted");
            return false;
        }
        try {
            LayoutInflater inflater = LayoutInflater.from(context);
            FrameLayout host = new FrameLayout(context);
            rootView = inflater.inflate(R.layout.floaty_layout, host, false);

            params = buildLayoutParams();
            bindViews();
            wireActions();

            windowManager.addView(rootView, params);
            showing = true;
            return true;
        } catch (RuntimeException e) {
            // BadTokenException / SecurityException on vendor ROMs.
            Log.e(TAG, "could not attach panel", e);
            showing = false;
            rootView = null;
            return false;
        }
    }

    /** Removes the panel. Safe to call repeatedly. */
    public void hide() {
        if (!showing || rootView == null || windowManager == null) {
            showing = false;
            return;
        }
        try {
            windowManager.removeView(rootView);
        } catch (RuntimeException e) {
            Log.w(TAG, "panel already detached", e);
        }
        showing = false;
        rootView = null;
    }

    public void toggle() {
        if (showing) {
            hide();
        } else {
            show();
        }
    }

    // ------------------------------------------------------------------
    // Window parameters
    // ------------------------------------------------------------------

    private WindowManager.LayoutParams buildLayoutParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(120);
        // Newer ROMs will not honour a fixed size for overlay windows otherwise.
        lp.width = dp(300);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    private void bindViews() {
        if (rootView == null) {
            return;
        }
        titleText = rootView.findViewById(R.id.title_text);
        stateText = rootView.findViewById(R.id.state_text);
        countSuccess = rootView.findViewById(R.id.count_success);
        countSkipped = rootView.findViewById(R.id.count_skipped);
        countErrors = rootView.findViewById(R.id.count_errors);
        retailerText = rootView.findViewById(R.id.retailer_text);
        messageText = rootView.findViewById(R.id.message_text);
        btnStart = rootView.findViewById(R.id.btn_start);
        btnPause = rootView.findViewById(R.id.btn_pause);
        btnReset = rootView.findViewById(R.id.btn_reset);
        btnCollapse = rootView.findViewById(R.id.btn_collapse);
        btnClose = rootView.findViewById(R.id.btn_close);
        btnPassword = rootView.findViewById(R.id.btn_password);
        statusDot = rootView.findViewById(R.id.status_dot);
        bodyContainer = rootView.findViewById(R.id.body_container);
    }

    private void wireActions() {
        if (rootView == null) {
            return;
        }
        View.OnClickListener click = v -> {
            if (v == null) {
                return;
            }
            int id = v.getId();
            if (id == R.id.btn_start) {
                dispatch(Command.START);
            } else if (id == R.id.btn_pause) {
                dispatch(Command.PAUSE);
            } else if (id == R.id.btn_reset) {
                dispatch(Command.RESET);
            } else if (id == R.id.btn_close) {
                dispatch(Command.CLOSE);
            } else if (id == R.id.btn_collapse) {
                toggleCollapsed();
            } else if (id == R.id.btn_password) {
                // Placeholder for the operator PIN: currently a harmless no-op so
                // a stray tap cannot stop a run.
            }
        };
        for (View v : new View[]{btnStart, btnPause, btnReset, btnPassword}) {
            if (v != null) {
                v.setOnClickListener(click);
            }
        }
        // ▾ and ✕ stay outside the drag zone so they can never be turned into a
        // "swipe the panel" gesture; they get their own click listeners.
        if (btnCollapse != null) {
            btnCollapse.setOnClickListener(click);
        }
        if (btnClose != null) {
            btnClose.setOnClickListener(click);
        }
        makeDraggable(rootView.findViewById(R.id.header_row));
    }

    private void dispatch(Command command) {
        if (listener != null) {
            listener.onCommand(command);
        }
    }

    private void toggleCollapsed() {
        collapsed = !collapsed;
        if (bodyContainer != null) {
            bodyContainer.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (btnCollapse != null) {
            btnCollapse.setText(collapsed ? "▸" : "▾");
        }
        // The panel width stays constant so the drag position survives.
    }

    /**
     * Drag by header. Uses {@link MotionEvent#getRawX()} because that is the
     * only coordinate space that is stable for overlay windows on ColorOS.
     */
    private void makeDraggable(View header) {
        if (header == null) {
            return;
        }
        header.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = event.getRawX();
                    touchDownY = event.getRawY();
                    windowDownX = params.x;
                    windowDownY = params.y;
                    dragging = false;
                    // Consume DOWN: on ColorOS an unconsumed DOWN on an overlay
                    // makes the whole gesture un-hittable for the child buttons.
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - touchDownX;
                    float dy = event.getRawY() - touchDownY;
                    if (!dragging && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) {
                        dragging = true;
                    }
                    if (dragging) {
                        params.x = Math.max(0, windowDownX + (int) dx);
                        params.y = Math.max(0, windowDownY + (int) dy);
                        try {
                            windowManager.updateViewLayout(rootView, params);
                        } catch (RuntimeException ignored) {
                            // window detached mid-drag
                        }
                    }
                    return dragging;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean wasDragging = dragging;
                    dragging = false;
                    return wasDragging;
                default:
                    return false;
            }
        });
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Paints a fresh snapshot. Never throws: the engine must keep running. */
    public void render(Snapshot s) {
        if (!showing || rootView == null || s == null) {
            return;
        }
        try {
            if (countSuccess != null) {
                countSuccess.setText(String.valueOf(s.success));
            }
            if (countSkipped != null) {
                countSkipped.setText(String.valueOf(s.skipped));
            }
            if (countErrors != null) {
                countErrors.setText(String.valueOf(s.errors));
            }
            if (retailerText != null) {
                String smr = s.currentSmr == null ? "—" : s.currentSmr;
                retailerText.setText("Retailers "
                        + Math.max(0, s.visibleRetailers) + "/" + s.totalRetailers
                        + "   ·   next: " + smr);
            }
            if (stateText != null) {
                stateText.setText(stateLine(s));
                stateText.setTextColor(stateColour(s));
            }
            if (messageText != null) {
                messageText.setText(s.message);
            }
            if (titleText != null) {
                titleText.setText(s.running ? (s.paused ? "SudhaAutoBot · paused" : "SudhaAutoBot · running")
                        : "SudhaAutoBot");
            }
            if (statusDot != null) {
                statusDot.setBackgroundColor(stateColour(s));
            }
            if (btnStart != null) {
                btnStart.setAlpha(s.running && !s.paused ? 0.45f : 1f);
                btnStart.setTypeface(Typeface.DEFAULT_BOLD);
            }
            if (btnPause != null) {
                btnPause.setAlpha(s.running && !s.paused ? 1f : 0.45f);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "render failed", e);
        }
    }

    private String stateLine(Snapshot s) {
        String base;
        switch (s.state) {
            case IDLE:
                base = "Idle · press ▶ START";
                break;
            case BOOT:
            case HOME:
                base = "Home · finding Customers";
                break;
            case CUSTOMERS:
                base = "Customer list · " + s.visibleRetailers + " SMR visible";
                break;
            case DETAILS:
                base = "Opening retailer details";
                break;
            case MILK:
                base = "Details · pressing Milk";
                break;
            case ORDER_HISTORY:
                base = "History · pressing +";
                break;
            case NEW_ORDER:
                base = "New order · Copy Order";
                break;
            case DATE_PICK:
                base = "Calendar · strict T-1..T-3";
                break;
            case COPY_VERIFY:
                base = "Verifying copied items";
                break;
            case PLACE_ORDER:
                base = "Placing order · payment chain";
                break;
            case CONFIRM_DONE:
                base = "Success · returning to list";
                break;
            case PAUSED:
                base = "Paused";
                break;
            case RECOVERY:
                base = "Recovering · back to list";
                break;
            case FINISHED:
                base = "Finished · " + s.success + " orders";
                break;
            default:
                base = s.state.name();
                break;
        }
        if (s.paused) {
            base = "⏸ " + base;
        }
        return base;
    }

    private int stateColour(Snapshot s) {
        if (s.paused || s.state == BotState.State.PAUSED) {
            return 0xFFFFB74D; // warn
        }
        switch (s.state) {
            case PLACE_ORDER:
            case CONFIRM_DONE:
                return 0xFF33D17A;
            case RECOVERY:
                return 0xFFFF5A5F;
            case FINISHED:
                return 0xFF2BD9A0;
            case IDLE:
                return 0xFF9AA7B4;
            default:
                return 0xFF2BD9A0;
        }
    }

    // ------------------------------------------------------------------
    // Convenience for the activity
    // ------------------------------------------------------------------

    /** Small line the activity can print when the panel is not attached. */
    public static String describe(Snapshot s) {
        if (s == null) {
            return "idle";
        }
        return s.state.name() + " · ok " + s.success + " · skip " + s.skipped + " · err " + s.errors;
    }

    /** Exposed for tests / debugging. */
    public View getRootView() {
        return rootView;
    }

    /** Kept for callers that want an explicit colour without a resource lookup. */
    public static int transparent() {
        return Color.TRANSPARENT;
    }
}
