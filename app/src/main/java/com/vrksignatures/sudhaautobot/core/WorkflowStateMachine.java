package com.vrksignatures.sudhaautobot.core;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The brain. One instance lives in {@link com.vrksignatures.sudhaautobot.SudhaAccessibilityService}
 * and is ticked on every relevant accessibility event and by a watchdog.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #tick()} decides from <b>the screen</b>, not from its own memory,
 *       so an operator tap in between can never desynchronise it.</li>
 *   <li>Every action is throttled ({@link #ACTION_GAP_MS}) and some actions have
 *       their own cool-down, so an event storm cannot double-tap
 *       "PLACE ORDER" or toggle "Show Retailers" twice.</li>
 *   <li>Progress is written to {@link DailyLedger} before moving on, so a
 *       kill -9 loses at most the retailer in flight.</li>
 *   <li>Nothing is ever clicked outside the configured target package.</li>
 * </ul>
 *
 * <h2>Flow</h2>
 * <pre>
 *  HOME ──Customers──▶ CUSTOMERS ──tap SMR row──▶ DETAILS ──Milk──▶ MILK
 *    ▲                    │                                        │ "+"
 *    │            (list collapsed? tap "Show Retailers")           ▼
 *    │                    │                                    ORDER_HISTORY
 *    │            (all rows resolved? scroll)                       │
 *    │                    │                                        ▼
 *    │                    └──────────────◀──────────────┐      NEW_ORDER
 *    │                                                  │       │ Copy Order
 *    │                                          CONFIRM_DONE    ▼
 *    │                                                  ▲      DATE_PICK  (T-1..T-3)
 *    │                                                  │       │
 *    │                                            PLACE_ORDER ◀─┴─▶ COPY_VERIFY
 *    │                                                  │        (empty → next date,
 *    └──────────────────────────────────────────────────┘          exhausted → SKIP)
 * </pre>
 */
public final class WorkflowStateMachine {

    /** Diagnostics sink for the floating panel / activity. */
    public interface Listener {
        /** Called whenever the visible state or its message changes. */
        void onStateChanged(BotState.State state, String message);
    }

    // ------------------------------------------------------------------
    // Vocabulary of the target app
    // ------------------------------------------------------------------

    private static final String[] HOME_CUSTOMERS = {"customers", "customer", "retailers", "retailer list"};
    private static final String[] SHOW_RETAILERS = {"show retailers", "show retailer", "show all retailers"};
    private static final String[] MILK_TAB = {"milk"};
    private static final String[] COPY_ORDER = {"copy order", "copy last order", "copy previous order", "copy"};
    private static final String[] PLACE_ORDER = {"place order", "confirm order", "place order & pay"};
    private static final String[] PAY = {"pay", "pay now", "make payment", "pay & place", "pay and place"};
    private static final String[] PROCEED = {"proceed", "continue", "next"};
    private static final String[] CONFIRM = {"confirm", "yes", "submit", "ok"};
    /**
     * Conclusive success wording. Deliberately excludes "thank you": polite
     * popups are shown before AND after confirmation, and marking SUCCESS too
     * early would silently lose an order.
     */
    private static final String[] SUCCESS_LABELS = {"order placed", "order successful", "placed successfully",
            "order confirmed", "success", "order has been placed", "order submitted"};
    /** "Done" is the button that closes a success screen; it is not proof by itself. */
    private static final String[] SUCCESS_DISMISS = {"done", "ok", "okay", "close", "continue"};
    private static final String[] NO_DATA_LABELS = {"no data", "no orders", "no previous order", "not found",
            "no record", "no order found", "nothing to copy"};
    private static final String[] CANCEL_LABELS = {"cancel", "close", "no", "dismiss"};
    private static final String[] OK_LABELS = {"ok", "okay", "got it", "yes", "continue"};

    /** Minimum gap between two taps: fast enough to finish 125 rows, slow enough for a shop phone. */
    public static final long ACTION_GAP_MS = 550L;
    /** A deep state that has not progressed for this long is unwound. */
    public static final long STUCK_TIMEOUT_MS = 12000L;
    /** Time given to the retailer list to expand after "Show Retailers". */
    public static final long EXPAND_TIMEOUT_MS = 5000L;
    /** Time given to copy-order quantities to render. */
    public static final long COPY_RENDER_TIMEOUT_MS = 3500L;
    /** Cool-down between two PLACE ORDER presses. */
    public static final long PLACE_ORDER_COOLDOWN_MS = 4000L;
    /** MAX PLACE ORDER presses before the retailer is declared failed. */
    public static final int MAX_PLACE_ORDER_PRESSES = 4;
    /** MAX BACK presses while unwinding. More than this means we are lost. */
    public static final int MAX_BACKTRACK_STEPS = 3;
    /** MAX scrolls while hunting for SMR rows on one screen. */
    public static final int MAX_LIST_SCROLLS = 30;

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private final AccessibilityService service;
    private final AccessibilityTree tree;
    private final DailyLedger ledger;
    private final CalendarDateSelector calendar;
    private final DateRollbackPolicy datePolicy;
    private final Listener listener;

    private volatile String targetPackage = "";

    private BotState.State state = BotState.State.IDLE;
    private BotState.Sub substep = BotState.Sub.NONE;
    private String message = "Idle";

    private boolean running;
    private boolean paused;
    /** Where to resume after PAUSE. */
    private BotState.State resumeState = BotState.State.HOME;

    private String currentSmr;
    /** True while currentSmr already has a SUCCESS row and must not be re-marked. */
    private boolean currentSmrHandled;
    private String lastDateTried = "";

    private long lastActionAt;
    private long lastProgressAt;
    private long stateEnteredAt;

    private long showRetailersTappedAt;
    private int showRetailersTaps;
    private long lastPlaceOrderAt;
    private long calendarConfirmAt;
    private int placeOrderPresses;

    private int dateAttempt;
    private int copyAttempts;
    private int listSwipes;
    private int backtrackSteps;
    private int visibleRetailers;

    public WorkflowStateMachine(AccessibilityService service,
                                AccessibilityTree tree,
                                DailyLedger ledger,
                                DateRollbackPolicy datePolicy,
                                Listener listener) {
        this.service = service;
        this.tree = tree;
        this.ledger = ledger;
        this.datePolicy = datePolicy == null ? new DateRollbackPolicy() : datePolicy;
        this.calendar = new CalendarDateSelector(tree, this.datePolicy);
        this.listener = listener;
        long now = System.currentTimeMillis();
        this.stateEnteredAt = now;
        this.lastProgressAt = now;
    }

    // ------------------------------------------------------------------
    // Control surface
    // ------------------------------------------------------------------

    public synchronized void setTargetPackage(String pkg) {
        this.targetPackage = pkg == null ? "" : pkg.trim();
    }

    public synchronized String getTargetPackage() {
        return targetPackage;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized BotState.State getState() {
        return state;
    }

    public synchronized BotState.Sub getSubstep() {
        return substep;
    }

    public synchronized String getMessage() {
        return message;
    }

    public synchronized String getCurrentSmr() {
        return currentSmr;
    }

    public synchronized int lastVisibleRetailers() {
        return visibleRetailers;
    }

    /** @return true while the watchdog should keep calling {@link #tick()}. */
    public synchronized boolean wantsTick() {
        return running && !paused;
    }

    /**
     * ▶ START. From idle this begins at the home screen and walks
     * Daily Orders → Customers (Step 1). From PAUSE it resumes the deep state it
     * was parked in, because the ledger already guarantees no retailer is
     * processed twice.
     */
    public synchronized void start() {
        if (running && !paused) {
            return;
        }
        boolean resuming = running && paused && currentSmr != null
                && resumeState != BotState.State.IDLE
                && resumeState != BotState.State.PAUSED
                && resumeState != BotState.State.HOME;
        running = true;
        paused = false;
        ledger.rolloverIfNeeded();
        lastProgressAt = System.currentTimeMillis();
        if (resuming) {
            enter(resumeState, substepOf(resumeState), "Resumed at " + resumeState.name()
                    + " · " + currentSmr);
        } else {
            currentSmr = null;
            resetAttemptBudget();
            enter(BotState.State.HOME, BotState.Sub.FIND_CUSTOMERS,
                    "Starting · looking for Customers");
        }
    }

    /** ⏸ PAUSE, remembering where we were. */
    public synchronized void pause() {
        if (!running || paused) {
            return;
        }
        if (state != BotState.State.PAUSED) {
            resumeState = state;
        }
        paused = true;
        enter(BotState.State.PAUSED, BotState.Sub.NONE, "Paused at " + resumeState.name());
    }

    public synchronized void togglePause() {
        if (running && !paused) {
            pause();
        } else {
            start();
        }
    }

    /** Stops the engine but keeps the ledger (and therefore today's progress). */
    public synchronized void resetEngine() {
        running = false;
        paused = false;
        currentSmr = null;
        currentSmrHandled = false;
        resetAttemptBudget();
        enter(BotState.State.IDLE, BotState.Sub.NONE, "Idle · press ▶ START");
    }

    /** Panel ↺ Reset: clears today's ledger, then stops. */
    public synchronized void resetLedgerAndStop() {
        ledger.resetToday();
        resetEngine();
        message = "Ledger cleared · press ▶ START for a fresh run";
        notifyListener();
    }

    private void resetAttemptBudget() {
        dateAttempt = 0;
        copyAttempts = 0;
        listSwipes = 0;
        backtrackSteps = 0;
        placeOrderPresses = 0;
        showRetailersTappedAt = 0;
        lastPlaceOrderAt = 0;
        calendarConfirmAt = 0;
        lastDateTried = "";
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /**
     * One decision. Cheap: a single tree walk at most, and usually far less
     * because the throttle returns immediately while the app is still catching
     * up with the previous tap.
     */
    public synchronized void tick() {
        if (!running || paused) {
            return;
        }
        long now = System.currentTimeMillis();
        if (ledger.rolloverIfNeeded()) {
            currentSmr = null;
            resetAttemptBudget();
            enter(BotState.State.HOME, BotState.Sub.FIND_CUSTOMERS, "New day · ledger rolled over");
        }
        if (now - lastActionAt < ACTION_GAP_MS) {
            return;
        }

        String foreground = tree.foregroundPackage();
        if (!isTargetForeground(foreground)) {
            setMessage("Waiting for " + shortName(targetPackage) + " to be in front");
            return;
        }

        try {
            dispatch();
        } catch (RuntimeException e) {
            // Vendor trees throw anything; never let it kill the service.
            stuck("Recovered from " + e.getClass().getSimpleName());
        }

        if (now - lastProgressAt > STUCK_TIMEOUT_MS && isDeepState(state)) {
            stuck("No progress in " + state.name() + " · unwinding");
        }
    }

    private void dispatch() {
        switch (state) {
            case BOOT:
            case HOME:
                stepHome();
                break;
            case CUSTOMERS:
                stepCustomers();
                break;
            case DETAILS:
                stepDetails();
                break;
            case MILK:
                stepMilk();
                break;
            case ORDER_HISTORY:
                stepOrderHistory();
                break;
            case NEW_ORDER:
                stepNewOrder();
                break;
            case DATE_PICK:
                stepDatePick();
                break;
            case COPY_VERIFY:
                stepCopyVerify();
                break;
            case PLACE_ORDER:
                stepPlaceOrder();
                break;
            case CONFIRM_DONE:
                stepConfirmDone();
                break;
            case RECOVERY:
                stepRecovery();
                break;
            case FINISHED:
                running = false;
                notifyListener();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Step 1: home -> Customers
    // ------------------------------------------------------------------

    private void stepHome() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        if (looksLikeRetailerList(root)) {
            enter(BotState.State.CUSTOMERS, BotState.Sub.EXPAND_LIST, "Customer list detected");
            return;
        }
        AccessibilityNodeInfo customers = tree.findClickableByTexts(root, HOME_CUSTOMERS);
        if (customers == null) {
            customers = tree.findByTextContains(root, "customer");
        }
        if (customers != null && tree.click(customers)) {
            acted("Opening Customers");
            enter(BotState.State.CUSTOMERS, BotState.Sub.EXPAND_LIST, "Customer list opening");
            return;
        }
        setMessage("Looking for the Customers module");
    }

    // ------------------------------------------------------------------
    // Steps 2 + 3: expand list, pick the next SMR
    // ------------------------------------------------------------------

    private void stepCustomers() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        // A leftover success/payment dialog after a resume must go first.
        if (dismissIfSuccessDialog(root)) {
            return;
        }
        if (!looksLikeRetailerList(root)) {
            // Not the list: go up one screen and re-enter through the home path.
            enter(BotState.State.HOME, BotState.Sub.FIND_CUSTOMERS, "Leaving · not the retailer list");
            return;
        }

        RetailerScan scan = scanRetailers(tree.flatten(root));
        visibleRetailers = scan.visibleIds.size();

        // ---- Step 2: "Show Retailers" ----------------------------------
        AccessibilityNodeInfo showRetailers = tree.findClickableByTexts(root, SHOW_RETAILERS);
        boolean collapsed = RetailerPolicy.shouldExpandList(
                scan.visibleRowCount, scan.visibleIds.size(), showRetailers != null);
        long now = System.currentTimeMillis();
        boolean cooledDown = now - showRetailersTappedAt > EXPAND_TIMEOUT_MS;

        if (collapsed && cooledDown && showRetailersTaps < 3) {
            if (tapShowRetailers(showRetailers)) {
                showRetailersTappedAt = now;
                showRetailersTaps++;
                acted("Show Retailers tapped (" + showRetailersTaps + ")");
                setMessage("Expanding list · " + scan.visibleIds.size() + " SMR visible");
                return;
            }
        }
        if (collapsed && !cooledDown) {
            setMessage("Expanding retailers · waiting for rows (" + scan.visibleIds.size() + ")");
            return;
        }

        setMessage("List · " + scan.visibleIds.size() + " SMR visible · "
                + ledger.countResolved() + " resolved");

        // ---- Step 3: next unprocessed SMR ------------------------------
        Set<String> finalIds = ledger.finalIds();
        for (RetailerHit hit : scan.hits) {
            if (!mayProcess(hit.smrId) || finalIds.contains(hit.smrId)) {
                continue;
            }
            if (ledger.get(hit.smrId) != null && !ledger.isPending(hit.smrId)) {
                continue; // failed too often today
            }
            if (tree.click(hit.anchor)) {
                beginRetailer(hit.smrId);
                return;
            }
        }

        // Everything visible is resolved: load more rows.
        boolean allVisibleFinal = !scan.hits.isEmpty();
        for (RetailerHit hit : scan.hits) {
            if (!finalIds.contains(hit.smrId)) {
                allVisibleFinal = false;
                break;
            }
        }
        if (!allVisibleFinal && listSwipes < MAX_LIST_SCROLLS && tree.scrollForward()) {
            listSwipes++;
            acted("Scrolling for more retailers");
            setMessage("Scrolling the retailer list (" + listSwipes + ")");
            return;
        }
        if (allVisibleFinal) {
            enter(BotState.State.FINISHED, BotState.Sub.NONE,
                    "All retailers handled · " + summary());
            return;
        }
        listSwipes = 0;
        if (scan.hits.isEmpty()) {
            setMessage("No SMR rows on this screen · retrying");
        } else {
            setMessage("Visible retailers are settled · waiting for the next screen");
        }
    }

    /** Taps the checkbox row, its parent, or its label — whichever is hittable. */
    private boolean tapShowRetailers(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (tree.forceClick(node)) {
            return true;
        }
        AccessibilityNodeInfo cur = tree.safeParent(node);
        for (int i = 0; i < 3 && cur != null; i++) {
            if (tree.tapCenter(cur)) {
                return true;
            }
            cur = tree.safeParent(cur);
        }
        return tree.tapCenter(node);
    }

    private void beginRetailer(String smrId) {
        currentSmr = smrId;
        currentSmrHandled = false;
        ledger.markInProgress(smrId, "opened from list");
        dateAttempt = 0;
        copyAttempts = 0;
        placeOrderPresses = 0;
        lastPlaceOrderAt = 0;
        lastDateTried = "";
        listSwipes = 0;
        acted("Opening " + smrId);
        enter(BotState.State.DETAILS, BotState.Sub.OPEN_MILK, "Opened " + smrId);
    }

    // ------------------------------------------------------------------
    // Step 4: details -> Milk -> + -> Copy Order
    // ------------------------------------------------------------------

    private void stepDetails() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        if (screenHas(root, COPY_ORDER)) {
            enter(BotState.State.NEW_ORDER, BotState.Sub.OPEN_COPY_ORDER, "Copy Order screen detected");
            return;
        }
        AccessibilityNodeInfo milk = tree.findClickableByTexts(root, MILK_TAB);
        if (milk == null) {
            milk = tree.findByTextContains(root, "milk");
        }
        if (milk != null && tree.click(milk)) {
            acted("Milk");
            enter(BotState.State.MILK, BotState.Sub.OPEN_MILK, "Opening Milk");
            return;
        }
        if (findAddButton(root) != null) {
            enter(BotState.State.ORDER_HISTORY, BotState.Sub.OPEN_NEW_ORDER, "Order history detected");
            return;
        }
        setMessage("Waiting for customer details · " + safe(currentSmr, "?"));
    }

    private void stepMilk() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        if (screenHas(root, COPY_ORDER)) {
            enter(BotState.State.NEW_ORDER, BotState.Sub.OPEN_COPY_ORDER, "Copy Order available");
            return;
        }
        AccessibilityNodeInfo fab = findAddButton(root);
        if (fab != null) {
            if (tree.click(fab) || tree.tapCenter(fab)) {
                acted("New order (+)");
                enter(BotState.State.NEW_ORDER, BotState.Sub.OPEN_COPY_ORDER, "New order sheet");
                return;
            }
        }
        AccessibilityNodeInfo milk = tree.findClickableByTexts(root, MILK_TAB);
        if (milk != null && tree.click(milk)) {
            acted("Milk");
            setMessage("Opening the Milk history");
            return;
        }
        setMessage("Waiting for the order history screen");
    }

    private void stepOrderHistory() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        if (screenHas(root, COPY_ORDER)) {
            enter(BotState.State.NEW_ORDER, BotState.Sub.OPEN_COPY_ORDER, "Copy Order available");
            return;
        }
        AccessibilityNodeInfo fab = findAddButton(root);
        if (fab != null && (tree.click(fab) || tree.tapCenter(fab))) {
            acted("New order (+)");
            enter(BotState.State.NEW_ORDER, BotState.Sub.OPEN_COPY_ORDER, "New order sheet");
            return;
        }
        AccessibilityNodeInfo plus = tree.findClickableByTexts(root, "+", "add", "new order", "add order");
        if (plus != null && tree.click(plus)) {
            acted("Add order");
            enter(BotState.State.NEW_ORDER, BotState.Sub.OPEN_COPY_ORDER, "New order sheet");
            return;
        }
        setMessage("Looking for the + button");
    }

    private void stepNewOrder() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        if (calendar.isCalendarVisible()) {
            enter(BotState.State.DATE_PICK, BotState.Sub.PICK_DATE, "Calendar is open");
            return;
        }
        AccessibilityNodeInfo copy = tree.findClickableByTexts(root, COPY_ORDER);
        if (copy == null) {
            copy = tree.findByTextContains(root, "copy order", "copy last", "copy previous");
        }
        if (copy != null && tree.click(copy)) {
            copyAttempts++;
            acted("Copy Order");
            enter(BotState.State.DATE_PICK, BotState.Sub.PICK_DATE,
                    "Copy Order pressed · will try " + describeAttempt());
            return;
        }
        if (positiveQuantityCount(root) > 0) {
            enter(BotState.State.COPY_VERIFY, BotState.Sub.AWAIT_ITEMS, "Quantities already present");
            return;
        }
        setMessage("Waiting for the Copy Order button");
    }

    // ------------------------------------------------------------------
    // Step 5: calendar with the strict T-1..T-3 rule
    // ------------------------------------------------------------------

    private void stepDatePick() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        if (dismissNoDataPopup()) {
            // The cursor has advanced; stay here if a calendar is still up,
            // otherwise re-open Copy Order for the next date.
            if (!calendar.isCalendarVisible()) {
                enter(BotState.State.NEW_ORDER, BotState.Sub.OPEN_COPY_ORDER,
                        "No data · reopening Copy Order for " + describeAttempt());
            } else {
                setMessage("No data there · trying " + describeAttempt());
            }
            return;
        }
        if (!calendar.isCalendarVisible()) {
            if (positiveQuantityCount(root) > 0) {
                enter(BotState.State.COPY_VERIFY, BotState.Sub.AWAIT_ITEMS, "Quantities appeared");
                return;
            }
            if (System.currentTimeMillis() - stateEnteredAt > 3000) {
                enter(BotState.State.NEW_ORDER, BotState.Sub.OPEN_COPY_ORDER,
                        "Calendar did not open · re-pressing Copy Order");
                return;
            }
            setMessage("Waiting for the calendar");
            return;
        }

        // Never page the calendar. A different month means stop, not guess.
        if (!calendar.isCurrentMonth()) {
            message = "Calendar is not on " + datePolicy.currentMonthHeader() + " · refusing to page";
            notifyListener();
            finishNoData("calendar not on the current month");
            return;
        }

        if (!endorseAttempt(dateAttempt)) {
            finishNoData("T-1..T-3 unavailable");
            return;
        }

        String day = String.valueOf(dayOfAttempt(dateAttempt));
        AccessibilityNodeInfo cell = calendar.pickCell(day);
        boolean tapped = cell != null && tree.click(cell);
        if (!tapped) {
            tapped = calendar.selectByGeometry(dateAttempt);
        }
        if (!tapped) {
            // The cell exists but refuses input: try the next legal day.
            String tried = describeAttempt();
            if (advanceAttempt()) {
                setMessage("Could not tap " + tried + " · next is " + describeAttempt());
                return;
            }
            finishNoData("no tappable date");
            return;
        }
        String chosen = describeAttempt();
        acted("Picked " + chosen);
        calendarConfirmAt = 0;
        enter(BotState.State.COPY_VERIFY, BotState.Sub.AWAIT_ITEMS, "Checking " + chosen);
    }

    /**
     * Strict rule gate: attempt {@code index} may be used only when its T-k day
     * is inside the current calendar month. (The bot never changes month, so a
     * previous-month day is simply not selectable.)
     */
    private boolean endorseAttempt(int index) {
        return datePolicy.offsetForAttempt(index) > 0;
    }

    private int calendarOffset(int index) {
        return datePolicy.offsetForAttempt(index);
    }

    private int dayOfAttempt(int index) {
        int offset = calendarOffset(index);
        return offset < 0 ? -1 : datePolicy.dayOfMonth(offset);
    }

    private String describeAttempt() {
        return calendarOffset(dateAttempt) < 0 ? "no legal date" : datePolicy.describeAttempt(dateAttempt);
    }

    /** Records the date just tried and moves the cursor to T-(k+1). */
    private boolean advanceAttempt() {
        int justTried = calendarOffset(dateAttempt);
        if (justTried > 0) {
            lastDateTried = datePolicy.isoDateOffset(justTried);
        } else if (dateAttempt == 0) {
            lastDateTried = datePolicy.isoDateOffset(1);
        }
        dateAttempt++;
        if (calendarOffset(dateAttempt) < 0) {
            return false;
        }
        setMessage("Next date · " + describeAttempt());
        return true;
    }

    // ------------------------------------------------------------------
    // Step 5b: did the copy produce anything?
    // ------------------------------------------------------------------

    private void stepCopyVerify() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        if (dismissNoDataPopup()) {
            return;
        }
        int items = positiveQuantityCount(root);
        if (items > 0) {
            acted("Copy OK · " + items + " quantities");
            enter(BotState.State.PLACE_ORDER, BotState.Sub.PAYMENT_CHAIN, "Placing the order");
            return;
        }
        // Some builds keep the calendar open until its OK/Apply button is
        // pressed: do that once, then keep waiting for the quantities.
        if (calendar.isCalendarVisible() && System.currentTimeMillis() - calendarConfirmAt > 1500) {
            calendarConfirmAt = System.currentTimeMillis();
            if (pressCalendarConfirm(root)) {
                acted("Confirmed the calendar");
                return;
            }
        }
        if (System.currentTimeMillis() - stateEnteredAt < COPY_RENDER_TIMEOUT_MS) {
            setMessage("Waiting for quantities · " + describeAttempt());
            return;
        }
        String tried = describeAttempt();
        if (advanceAttempt()) {
            enter(BotState.State.NEW_ORDER, BotState.Sub.OPEN_COPY_ORDER,
                    "Empty " + tried + " · trying " + describeAttempt());
            return;
        }
        finishNoData("no quantities T-1..T-3");
    }

    /** Marks SKIPPED_NO_DATA, cancels the order form and walks back to the list. */
    private void finishNoData(String reason) {
        if (currentSmr != null && !currentSmrHandled) {
            ledger.markSkippedNoData(currentSmr, lastDateTried, reason);
            acted("Skipped " + currentSmr + " · " + reason);
        }
        currentSmr = null;
        currentSmrHandled = false;
        resetAttemptBudget();
        cancelPicker();
        backtrackSteps = 0;
        enter(BotState.State.RECOVERY, BotState.Sub.BACKTRACK, "No data · back to the list");
    }

    /** OK / APPLY / SET / DONE inside the date dialog, when the build wants one. */
    private boolean pressCalendarConfirm(AccessibilityNodeInfo root) {
        String[] labels = {"ok", "okay", "apply", "set", "done", "select", "confirm"};
        AccessibilityNodeInfo button = tree.findClickableByTexts(root, labels);
        return button != null && (tree.click(button) || tree.tapCenter(button));
    }

    private void cancelPicker() {
        for (AccessibilityNodeInfo root : tree.windowRoots()) {
            AccessibilityNodeInfo cancel = tree.findClickableByTexts(root, CANCEL_LABELS);
            if (cancel != null && tree.click(cancel)) {
                acted("Cancel");
                return;
            }
        }
        tree.pressBack();
    }

    // ------------------------------------------------------------------
    // Step 6: PLACE ORDER + payment chain
    // ------------------------------------------------------------------

    private void stepPlaceOrder() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        if (isSuccessScreen(root)) {
            AccessibilityNodeInfo done = tree.findClickableByTexts(root, SUCCESS_DISMISS);
            if (done == null || tree.click(done) || tree.tapCenter(done)) {
                onSuccess();
                return;
            }
        }

        long now = System.currentTimeMillis();
        AccessibilityNodeInfo place = tree.findClickableByTexts(root, PLACE_ORDER);
        if (place == null) {
            place = tree.findByTextContains(root, "place order");
        }
        boolean cooledDown = now - lastPlaceOrderAt > PLACE_ORDER_COOLDOWN_MS;
        if (place != null && cooledDown && placeOrderPresses < MAX_PLACE_ORDER_PRESSES) {
            if (tree.click(place) || tree.tapCenter(place)) {
                placeOrderPresses++;
                lastPlaceOrderAt = now;
                acted("PLACE ORDER (" + placeOrderPresses + ")");
                setMessage("Placing order…");
                return;
            }
        }
        if (place != null && !cooledDown) {
            setMessage("Waiting for PLACE ORDER to be accepted");
            return;
        }
        if (handlePaymentChain(root)) {
            return;
        }
        if (placeOrderPresses >= MAX_PLACE_ORDER_PRESSES) {
            markFailedAndRecover("order not confirmed after " + placeOrderPresses + " presses");
            return;
        }
        setMessage("Looking for PLACE ORDER");
    }

    /** Presses at most one button of PLACE ORDER → PAY → PROCEED → CONFIRM. */
    private boolean handlePaymentChain(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        if (isSuccessScreen(root)) {
            onSuccess();
            return true;
        }
        String[][] chain = {PAY, PROCEED, CONFIRM};
        for (String[] labels : chain) {
            AccessibilityNodeInfo button = tree.findClickableByTexts(root, labels);
            if (button != null && (tree.click(button) || tree.tapCenter(button))) {
                acted("Payment step: " + tree.labelOf(button));
                setMessage("Confirming · " + tree.labelOf(button));
                return true;
            }
        }
        return false;
    }

    /** Records SUCCESS and moves to CONFIRM_DONE. Call when the order is confirmed. */
    private void onSuccess() {
        recordSuccess();
        resetAttemptBudget();
        enter(BotState.State.CONFIRM_DONE, BotState.Sub.DISMISS_SUCCESS,
                "Order placed · " + summary());
    }

    /**
     * Writes the SUCCESS row without touching the state machine. Used when a
     * success screen is found in the middle of another step: the row must be
     * committed before anything else, but the state must stay pointed at the
     * screen that is actually in front of us.
     */
    private void recordSuccess() {
        if (currentSmr == null) {
            return;
        }
        String tried = lastDateTried.isEmpty() ? datePolicy.isoDateOffset(1) : lastDateTried;
        ledger.markSuccess(currentSmr, tried, "order placed");
        acted("SUCCESS " + currentSmr);
        // currentSmr intentionally kept: finishNoData()/markFailed() must not
        // overwrite the SUCCESS row while this retailer is still in flight.
        currentSmrHandled = true;
    }

    private void stepConfirmDone() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo done = tree.findClickableByTexts(root, SUCCESS_DISMISS);
        if (done == null) {
            done = tree.findClickableByTexts(root, new String[]{"done", "ok", "okay", "continue"});
        }
        if (done != null && (tree.click(done) || tree.tapCenter(done))) {
            acted("Done");
            enter(BotState.State.RECOVERY, BotState.Sub.BACKTRACK, "Back to the retailer list");
            return;
        }
        if (looksLikeRetailerList(root)) {
            enter(BotState.State.CUSTOMERS, BotState.Sub.EXPAND_LIST, "Back on the retailer list");
            return;
        }
        enter(BotState.State.RECOVERY, BotState.Sub.BACKTRACK, "Returning to the list");
    }

    // ------------------------------------------------------------------
    // Recovery
    // ------------------------------------------------------------------

    private void stepRecovery() {
        AccessibilityNodeInfo root = tree.activeRoot();
        if (root == null) {
            return;
        }
        if (dismissNoDataPopup()) {
            return;
        }
        if (dismissIfSuccessDialog(root)) {
            return;
        }
        if (looksLikeRetailerList(root)) {
            backtrackSteps = 0;
            listSwipes = 0;
            enter(BotState.State.CUSTOMERS, BotState.Sub.EXPAND_LIST, "Back on the retailer list");
            return;
        }
        // Let the screen settle before touching it: the list repaints after a
        // placed order and a BACK during that repaint skips the list entirely.
        if (System.currentTimeMillis() - stateEnteredAt < 1200) {
            setMessage("Waiting for the list to render");
            return;
        }
        AccessibilityNodeInfo cancel = tree.findClickableByTexts(root, CANCEL_LABELS);
        if (cancel != null && tree.click(cancel)) {
            acted("Cancel");
            return;
        }
        if (backtrackSteps < MAX_BACKTRACK_STEPS) {
            backtrackSteps++;
            tree.pressBack();
            acted("Back (" + backtrackSteps + ")");
            return;
        }
        markFailedAndRecover("could not get back to the list");
    }

    private void markFailedAndRecover(String reason) {
        if (currentSmr != null && !currentSmrHandled) {
            ledger.markFailed(currentSmr, reason);
            acted("FAILED " + currentSmr + " · " + reason);
        }
        currentSmr = null;
        currentSmrHandled = false;
        resetAttemptBudget();
        backtrackSteps = 0;
        enter(BotState.State.HOME, BotState.Sub.FIND_CUSTOMERS, "Restarting from home");
    }

    // ------------------------------------------------------------------
    // Screen recognition
    // ------------------------------------------------------------------

    private boolean isTargetForeground(String foreground) {
        String target = targetPackage;
        if (target == null || target.isEmpty()) {
            return true; // nothing configured: do not block the run
        }
        return foreground != null && foreground.equals(target);
    }

    private boolean screenHas(AccessibilityNodeInfo root, String[] labels) {
        return root != null && tree.findByTexts(root, labels) != null;
    }

    /** The retailer list is recognisable by its "Show Retailers" row or 2+ SMR rows. */
    private boolean looksLikeRetailerList(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        if (tree.findByTexts(root, SHOW_RETAILERS) != null) {
            return true;
        }
        return scanRetailers(tree.flatten(root)).hits.size() >= 2;
    }

    /**
     * A success screen must carry a conclusive phrase. "thank you" alone is not
     * enough; it is only accepted together with an order/placed word.
     */
    private boolean isSuccessScreen(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (String label : tree.allLabels(root)) {
            sb.append(label).append(' ');
        }
        String joined = sb.toString().toLowerCase(Locale.ROOT);
        for (String label : SUCCESS_LABELS) {
            if (joined.contains(label)) {
                return true;
            }
        }
        return joined.contains("thank you")
                && (joined.contains("order") || joined.contains("placed") || joined.contains("delivery"));
    }

    private boolean dismissIfSuccessDialog(AccessibilityNodeInfo root) {
        if (!isSuccessScreen(root)) {
            return false;
        }
        // Reached outside PLACE_ORDER (e.g. right after a resume): commit the
        // row first, otherwise the same order would be placed twice.
        if (state != BotState.State.CONFIRM_DONE && currentSmr != null) {
            recordSuccess();
            resetAttemptBudget();
            enter(BotState.State.CONFIRM_DONE, BotState.Sub.DISMISS_SUCCESS,
                    "Order placed · " + summary());
            return true;
        }
        AccessibilityNodeInfo done = tree.findClickableByTexts(root, SUCCESS_DISMISS);
        if (done == null) {
            done = tree.findClickableByTexts(root, SUCCESS_LABELS);
        }
        if (done != null && tree.click(done)) {
            acted("Dismissed success dialog");
            return true;
        }
        return false;
    }

    /**
     * Finds and dismisses a "no data" alert. When one is found the date cursor
     * advances (that date had nothing) and true is returned.
     */
    private boolean dismissNoDataPopup() {
        for (AccessibilityNodeInfo root : tree.windowRoots()) {
            AccessibilityNodeInfo label = tree.findByTextContains(root, NO_DATA_LABELS);
            if (label == null) {
                continue;
            }
            AccessibilityNodeInfo ok = tree.findClickableByTexts(root, OK_LABELS);
            if (ok != null && tree.click(ok)) {
                acted("Dismissed the 'no data' alert");
                advanceAttempt();
                return true;
            }
            // No button: BACK closes the dialog on this app.
            if (tree.pressBack()) {
                acted("Closed the 'no data' alert");
                advanceAttempt();
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Quantity probing
    // ------------------------------------------------------------------

    /**
     * Counts labels that read like a real quantity ("2", "1.5 L", "500 ml",
     * "3 pkt") outside the top 18% of the screen, which is where the app keeps
     * its title bar and totals. A copy-order that worked shows at least one.
     */
    private int positiveQuantityCount(AccessibilityNodeInfo root) {
        if (root == null) {
            return 0;
        }
        Rect screen = tree.screenBounds();
        int topBar = screen.height() * 18 / 100;
        int count = 0;
        for (AccessibilityNodeInfo node : tree.flatten(root)) {
            if (!tree.isVisible(node)) {
                continue;
            }
            Rect bounds = tree.boundsOf(node);
            if (bounds == null || bounds.centerY() < topBar) {
                continue;
            }
            Double value = parseQuantity(tree.labelOf(node));
            if (value != null && value > 0d) {
                count++;
            }
            if (count >= 12) {
                break;
            }
        }
        return count;
    }

    private static final Pattern QUANTITY = Pattern.compile(
            "^([0-9]{1,3}(?:[.,][0-9]{1,2})?)\\s*(?:l|lt|ltr|litre|liter|ml|kg|gm|g|pkt|pack|pcs|pc|unit|units)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * JVM-testable seam for {@link #parseQuantity}. Production code calls the
     * package-private method directly.
     */
    public static Double parseQuantityForTest(String label) {
        return parseQuantity(label);
    }

    /** @return the numeric value of a quantity-looking label, else null. */
    static Double parseQuantity(String label) {
        if (label == null) {
            return null;
        }
        String text = label.trim();
        if (text.isEmpty() || text.length() > 10) {
            return null;
        }
        Matcher m = QUANTITY.matcher(text);
        if (!m.matches()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Retailer scanning
    // ------------------------------------------------------------------

    /** One clickable retailer row. */
    private static final class RetailerHit {
        final String smrId;
        final AccessibilityNodeInfo anchor;

        RetailerHit(String smrId, AccessibilityNodeInfo anchor) {
            this.smrId = smrId;
            this.anchor = anchor;
        }
    }

    /** One pass over the visible retailer list. */
    private static final class RetailerScan {
        final List<RetailerHit> hits = new ArrayList<>();
        final Set<String> visibleIds = new LinkedHashSet<>();
        int visibleRowCount;
    }

    /**
     * Turns the visible tree into an ordered list of retailer rows.
     *
     * <p>Every label is offered to {@link RetailerPolicy#extractSmrId}, which is
     * what keeps "SM65" and "65" out. The click anchor is the nearest clickable
     * ancestor of the label, i.e. the row itself.</p>
     */
    private RetailerScan scanRetailers(List<AccessibilityNodeInfo> nodes) {
        RetailerScan scan = new RetailerScan();
        if (nodes == null) {
            return scan;
        }
        List<AccessibilityNodeInfo> labelled = new ArrayList<>();
        for (AccessibilityNodeInfo node : nodes) {
            if (!tree.isVisible(node)) {
                continue;
            }
            String label = tree.labelOf(node);
            if (label.isEmpty() || RetailerPolicy.isDistributorOrNumeric(label)) {
                continue;
            }
            String id = RetailerPolicy.extractSmrId(label);
            if (id == null) {
                continue;
            }
            labelled.add(node);
            if (scan.visibleIds.add(id)) {
                scan.visibleRowCount++;
            }
        }
        // Strictly top-to-bottom: the list must be walked in reading order.
        labelled.sort((a, b) -> {
            Rect ra = tree.boundsOf(a);
            Rect rb = tree.boundsOf(b);
            if (ra == null || rb == null) {
                return 0;
            }
            return Integer.compare(ra.top, rb.top);
        });
        Set<String> seen = new LinkedHashSet<>();
        for (AccessibilityNodeInfo node : labelled) {
            String id = RetailerPolicy.extractSmrId(tree.labelOf(node));
            if (id == null || !seen.add(id)) {
                continue;
            }
            AccessibilityNodeInfo anchor = tree.closestClickable(node);
            scan.hits.add(new RetailerHit(id, anchor == null ? node : anchor));
        }
        return scan;
    }

    /** Operator hook: every SMR id is allowed by default. */
    private boolean mayProcess(String smrId) {
        return smrId != null && !smrId.isEmpty();
    }

    // ------------------------------------------------------------------
    // Control finding
    // ------------------------------------------------------------------

    /**
     * Finds a "+" control: described FAB first, then a literal "+" label, then a
     * clickable square parked in the bottom-right corner.
     */
    private AccessibilityNodeInfo findAddButton(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        List<AccessibilityNodeInfo> nodes = tree.flatten(root);
        for (AccessibilityNodeInfo node : nodes) {
            if (!tree.isVisible(node)) {
                continue;
            }
            String label = tree.labelOf(node).toLowerCase(Locale.ROOT);
            String id = tree.viewIdOf(node).toLowerCase(Locale.ROOT);
            if (label.equals("+") || label.equals("＋") || label.equals("add")
                    || label.contains("add order") || label.contains("new order")) {
                return node;
            }
            if (id.endsWith("/fab") || id.contains("fab") || id.contains("add_button")) {
                return node;
            }
        }
        Rect screen = tree.screenBounds();
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            Rect r = tree.boundsOf(node);
            if (r == null || !tree.isVisible(node)) {
                continue;
            }
            boolean squareish = Math.abs(r.width() - r.height()) < Math.max(1, r.width() / 2);
            if (!squareish || r.width() < 40 || r.width() > screen.width() / 3) {
                continue;
            }
            if (r.centerX() < screen.width() * 0.6f || r.centerY() < screen.height() * 0.55f) {
                continue;
            }
            int score = (screen.width() - r.centerX()) + (screen.height() - r.centerY());
            if (score < bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private boolean isDeepState(BotState.State candidate) {
        switch (candidate) {
            case DETAILS:
            case MILK:
            case ORDER_HISTORY:
            case NEW_ORDER:
            case DATE_PICK:
            case COPY_VERIFY:
            case PLACE_ORDER:
            case CONFIRM_DONE:
                return true;
            default:
                return false;
        }
    }

    private BotState.Sub substepOf(BotState.State candidate) {
        switch (candidate) {
            case DETAILS:
                return BotState.Sub.OPEN_MILK;
            case MILK:
                return BotState.Sub.OPEN_MILK;
            case ORDER_HISTORY:
                return BotState.Sub.OPEN_NEW_ORDER;
            case NEW_ORDER:
                return BotState.Sub.OPEN_COPY_ORDER;
            case DATE_PICK:
                return BotState.Sub.PICK_DATE;
            case COPY_VERIFY:
                return BotState.Sub.AWAIT_ITEMS;
            case PLACE_ORDER:
                return BotState.Sub.PAYMENT_CHAIN;
            case CONFIRM_DONE:
                return BotState.Sub.DISMISS_SUCCESS;
            default:
                return BotState.Sub.NONE;
        }
    }

    private void stuck(String why) {
        message = why;
        backtrackSteps = 0;
        enter(BotState.State.RECOVERY, BotState.Sub.BACKTRACK, why);
    }

    private void enter(BotState.State newState, BotState.Sub newSub, String newMessage) {
        boolean changed = newState != state || newSub != substep;
        state = newState;
        substep = newSub;
        message = newMessage;
        lastProgressAt = System.currentTimeMillis();
        if (changed) {
            stateEnteredAt = lastProgressAt;
        }
        if (newState == BotState.State.CUSTOMERS) {
            // The scroll budget is per visit of the list, not global.
            listSwipes = 0;
        }
        notifyListener();
    }

    private void setMessage(String newMessage) {
        if (newMessage == null || newMessage.equals(message)) {
            return;
        }
        message = newMessage;
        lastProgressAt = System.currentTimeMillis();
        notifyListener();
    }

    /** Records a tap: consumes the action budget and refreshes the stuck timer. */
    private void acted(String newMessage) {
        lastActionAt = System.currentTimeMillis();
        lastProgressAt = lastActionAt;
        message = newMessage;
        notifyListener();
    }

    private void notifyListener() {
        if (listener == null) {
            return;
        }
        try {
            listener.onStateChanged(state, message);
        } catch (RuntimeException ignored) {
            // the UI must never break the engine
        }
    }

    private String summary() {
        return ledger.countSuccess() + " ok / " + ledger.countSkipped() + " skipped / "
                + ledger.countErrors() + " errors";
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private String shortName(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "the target app";
        }
        int dot = pkg.lastIndexOf('.');
        return dot >= 0 ? pkg.substring(dot + 1) : pkg;
    }

    /** Exposed for diagnostics; the service logs it on demand. */
    public synchronized String describe() {
        return state.name() + "/" + substep.name() + " smr=" + safe(currentSmr, "-")
                + " attempts=" + (dateAttempt + 1) + "/3";
    }

    /** Unused but kept meaningful: the service is the only AccessibilityService user. */
    AccessibilityService service() {
        return service;
    }
}
