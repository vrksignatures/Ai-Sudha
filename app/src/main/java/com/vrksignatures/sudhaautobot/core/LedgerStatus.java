package com.vrksignatures.sudhaautobot.core;

/**
 * Per-retailer outcome for the current day. The values are what the floating
 * panel counts and what {@link RetailerPolicy} uses to decide whether a row may
 * still be clicked:
 *
 * <ul>
 *   <li>{@link #SUCCESS}, {@link #SKIPPED_NO_DATA} and {@link #SKIPPED_EXCLUDED}
 *       are <b>final</b> — the bot will never touch that SMR again today.</li>
 *   <li>{@link #IN_PROGRESS} means "we clicked it once"; if the app dies and the
 *       bot restarts after {@link RetailerPolicy#IN_PROGRESS_STALE_MS} the entry
 *       is demoted so it can be retried.</li>
 *   <li>{@link #FAILED} is retryable up to {@link RetailerPolicy#MAX_ATTEMPTS}.</li>
 * </ul>
 */
public enum LedgerStatus {
    /** Order was placed and the success screen confirmed it. Final. */
    SUCCESS(true),
    /** No copy-order data on T-1, T-2 or T-3. Final for the day. */
    SKIPPED_NO_DATA(true),
    /** Operator/retailer policy excluded it (paused retailer, wrong ID...). Final. */
    SKIPPED_EXCLUDED(true),
    /** Currently being processed. Retryable only after it goes stale. */
    IN_PROGRESS(false),
    /** Something went wrong. Retryable until MAX_ATTEMPTS. */
    FAILED(false);

    private final boolean finalForDay;

    LedgerStatus(boolean finalForDay) {
        this.finalForDay = finalForDay;
    }

    /**
     * @return true when {@link RetailerPolicy} must hide this retailer for the
     *         rest of the day (success or an explicit skip).
     */
    public boolean isFinalForDay() {
        return finalForDay;
    }

    /** @return true when the panel's ERROR counter should include this row. */
    public boolean isError() {
        return this == FAILED;
    }

    /** @return true when the panel's SKIPPED counter should include this row. */
    public boolean isSkipped() {
        return this == SKIPPED_NO_DATA || this == SKIPPED_EXCLUDED;
    }
}
