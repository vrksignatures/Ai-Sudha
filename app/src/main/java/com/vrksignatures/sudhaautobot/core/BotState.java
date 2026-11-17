package com.vrksignatures.sudhaautobot.core;

/**
 * The single source of truth for "where is the bot right now".
 *
 * <p>The engine is an event-driven loop: every accessibility event (or the
 * watchdog tick when nothing arrives) asks {@link WorkflowStateMachine} to
 * decide the next {@code ACTION_*} for the current screen. The primary
 * {@link State} plus the {@link Sub} step inside it describe which screen the
 * bot expects to see. Nothing else in the codebase is allowed to keep its own
 * notion of progress, which is what makes pause/resume safe.</p>
 *
 * <p>States are deliberately coarse. The target app is a third-party UI that
 * can be reskinned without notice, so instead of memorising one exact
 * activity, the machine recognises a screen by a small set of anchors
 * ("Milk" + "Copy Order" + a "+" button, ...) and re-syncs from anywhere.</p>
 */
public final class BotState {

    private BotState() {
    }

    /** Coarse workflow position. */
    public enum State {
        /** Not running. Nothing is touched. */
        IDLE,
        /** Operator pressed START; waiting for the target app to be in front. */
        BOOT,
        /** Looking for the "Customers" entry point on the home screen. */
        HOME,
        /** Retailer list is on screen; expanding it and hunting for SMRs. */
        CUSTOMERS,
        /** A retailer row was tapped; waiting for the details screen. */
        DETAILS,
        /** Details screen is open; pressing "Milk". */
        MILK,
        /** Order history is open; pressing the floating "+" / FAB. */
        ORDER_HISTORY,
        /** "New Order Milk" is open; pressing "Copy Order". */
        NEW_ORDER,
        /** Calendar dialog is up; choosing a T-k date. */
        DATE_PICK,
        /** A copy was accepted; verifying that item rows really appeared. */
        COPY_VERIFY,
        /** Items are on screen; pressing PLACE ORDER and the payment chain. */
        PLACE_ORDER,
        /** Success screen is up; pressing "Done" and walking back to the list. */
        CONFIRM_DONE,
        /** Operator paused. Current state is preserved for resume. */
        PAUSED,
        /** All retailers handled (or operator reset). */
        FINISHED,
        /** Unrecoverable/unknown screen: unwinding back to the retailer list. */
        RECOVERY
    }

    /** Fine-grained step inside the current {@link State}. */
    public enum Sub {
        NONE,
        /** Home screen: hunting for the Customers module. */
        FIND_CUSTOMERS,
        /** Retailer list: scrolling/expanding the list ("Show Retailers"). */
        EXPAND_LIST,
        /** Retailer list: choosing the next unprocessed SMR. */
        SELECT_RETAILER,
        /** Details: pressing "Milk". */
        OPEN_MILK,
        /** Order history: pressing "+". */
        OPEN_NEW_ORDER,
        /** New order: pressing "Copy Order". */
        OPEN_COPY_ORDER,
        /** Calendar: trying T-1..T-3. */
        PICK_DATE,
        /** Checking whether the copy produced any quantity. */
        AWAIT_ITEMS,
        /** Payment chain: PLACE ORDER -> PROCEED -> PAY -> CONFIRM -> OK. */
        PAYMENT_CHAIN,
        /** Success: pressing Done / back to the list. */
        DISMISS_SUCCESS,
        /** Unwinding with BACK until the retailer list is recognised again. */
        BACKTRACK
    }
}
