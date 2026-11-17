package com.vrksignatures.sudhaautobot.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The strict "3-day rule" for Copy Order.
 *
 * <p>Busy retailers often skip a day. Sudha's Copy Order can replay any past
 * day, so the bot tries yesterday first, then the day before, then three days
 * back. It never jumps a week, never guesses, and <b>never changes the calendar
 * month</b>: if T-3 falls in the previous month the bot gives up instead of
 * paging the calendar (that path is where every macro like this one breaks).</p>
 *
 * <pre>
 *   today    = 2026-11-17
 *   attempts = 2026-11-16 (T-1), 2026-11-15 (T-2), 2026-11-14 (T-3)
 * </pre>
 */
public final class DateRollbackPolicy {

    /** How far back the bot is allowed to look. */
    public static final int MAX_LOOKBACK_DAYS = 3;

    private static final String[] MONTHS_LONG = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
    };
    private static final String[] MONTHS_SHORT = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private final Calendar today;

    public DateRollbackPolicy() {
        this(Calendar.getInstance());
    }

    /** Injectable clock — lets the policy be tested without a device. */
    public DateRollbackPolicy(Calendar today) {
        this.today = (Calendar) today.clone();
        this.today.set(Calendar.HOUR_OF_DAY, 12);
        this.today.set(Calendar.MINUTE, 0);
        this.today.set(Calendar.SECOND, 0);
        this.today.set(Calendar.MILLISECOND, 0);
    }

    /** Refreshes the policy's notion of "today" (called on day rollover). */
    public static Calendar currentDay() {
        return Calendar.getInstance();
    }

    /**
     * The calendar cell labels to look for, in the order they must be tried.
     *
     * @return T-1, T-2, T-3 formatted per {@link #cellLabelFormat()}, e.g.
     *         ["16", "15", "14"].
     */
    public List<String> attemptLabels() {
        List<String> out = new ArrayList<>(MAX_LOOKBACK_DAYS);
        for (int i = 1; i <= MAX_LOOKBACK_DAYS; i++) {
            out.add(cellDayLabel(dayOffset(i)));
        }
        return out;
    }

    /**
     * Calendar cells only print the day-of-month ("16"), so that is what the
     * bot taps. {@link #cellLabelFormat()} documents the expectation for a
     * future app version that prints "16 Nov".
     */
    public String cellLabelFormat() {
        return "d";
    }

    /** @return the day-of-month text shown on the calendar cell, e.g. "16". */
    public String cellDayLabel(Calendar day) {
        return new SimpleDateFormat(cellLabelFormat(), Locale.US).format(day.getTime());
    }

    /** @return T-k at noon, so DST shifts can never move the date. */
    public Calendar dayOffset(int k) {
        Calendar c = (Calendar) today.clone();
        c.add(Calendar.DAY_OF_YEAR, -k);
        return c;
    }

    /** @return T-k as an ISO date, the form stored in the ledger. */
    public String isoDateOffset(int k) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(dayOffset(k).getTime());
    }

    /** @return T-k as a human label used in the floating panel: "Nov 16". */
    public String displayDateOffset(int k) {
        return new SimpleDateFormat("MMM d", Locale.US).format(dayOffset(k).getTime());
    }

    /** @return the month name (long) of T-k, "November". */
    public String monthNameOffset(int k) {
        return MONTHS_LONG[dayOffset(k).get(Calendar.MONTH)];
    }

    /** @return the month name (short) of T-k, "Nov". */
    public String shortMonthOffset(int k) {
        return MONTHS_SHORT[dayOffset(k).get(Calendar.MONTH)];
    }

    /** @return the month name (long) of today. */
    public String currentMonthName() {
        return MONTHS_LONG[today.get(Calendar.MONTH)];
    }

    /** @return the month name (short) of today. */
    public String currentShortMonth() {
        return MONTHS_SHORT[today.get(Calendar.MONTH)];
    }

    /** @return the 1-based month of today. */
    public int currentMonth() {
        return today.get(Calendar.MONTH) + 1;
    }

    /** @return the year of today. */
    public int currentYear() {
        return today.get(Calendar.YEAR);
    }

    /** @return the day-of-month of T-k. */
    public int dayOfMonth(int k) {
        return dayOffset(k).get(Calendar.DAY_OF_MONTH);
    }

    /**
     * True when T-k lives in the same month as today. The bot refuses to touch
     * the calendar's arrows, so a different month means "not selectable".
     */
    public boolean isSameMonthAsToday(int k) {
        Calendar d = dayOffset(k);
        return d.get(Calendar.MONTH) == today.get(Calendar.MONTH)
                && d.get(Calendar.YEAR) == today.get(Calendar.YEAR);
    }

    /** True when ALL of T-1..T-3 are in the current month. */
    public boolean allAttemptsInCurrentMonth() {
        for (int i = 1; i <= MAX_LOOKBACK_DAYS; i++) {
            if (!isSameMonthAsToday(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Attempts that are legal today, in order.
     *
     * <p>A T-k that falls in the <b>previous month</b> is dropped (we never page
     * the calendar), and so is everything further back: the bot must not skip
     * over an unreachable day. Earlier days of the <i>current</i> month stay
     * legal, e.g. on 2 Nov the sequence is T-1 (1 Nov), T-2 (31 Oct — dropped),
     * so exactly one attempt.</p>
     */
    public List<Integer> legalOffsets() {
        List<Integer> out = new ArrayList<>(MAX_LOOKBACK_DAYS);
        for (int i = 1; i <= MAX_LOOKBACK_DAYS; i++) {
            if (!isSameMonthAsToday(i)) {
                break;
            }
            out.add(i);
        }
        return out;
    }

    /** Label for the calendar header, "November 2026". */
    public String currentMonthHeader() {
        return MONTHS_LONG[today.get(Calendar.MONTH)] + " " + today.get(Calendar.YEAR);
    }

    /** True when every legal offset has already been tried. */
    public boolean exhausted(int nextAttemptIndex) {
        return nextAttemptIndex >= legalOffsets().size();
    }

    /**
     * Advance the attempt cursor.
     *
     * @param current 0-based index of the attempt that just failed
     * @return the next index to try, or -1 when T-3 was also empty
     */
    public int nextAttempt(int current) {
        int next = current + 1;
        List<Integer> legal = legalOffsets();
        if (next >= legal.size()) {
            return -1;
        }
        return next;
    }

    /** @return the offset (1..3) for a 0-based attempt index, or -1. */
    public int offsetForAttempt(int attemptIndex) {
        List<Integer> legal = legalOffsets();
        if (attemptIndex < 0 || attemptIndex >= legal.size()) {
            return -1;
        }
        return legal.get(attemptIndex);
    }

    /** Friendly one-liner for logs/panel: "T-2 = Nov 15". */
    public String describeAttempt(int attemptIndex) {
        int offset = offsetForAttempt(attemptIndex);
        if (offset < 0) {
            return "T-? (exhausted)";
        }
        return "T-" + offset + " = " + displayDateOffset(offset);
    }

    /** The epoch-millis of T-k, for the digits fallback. */
    public long millisOffset(int k) {
        return dayOffset(k).getTimeInMillis();
    }

    /** True when the two labels refer to the same calendar day (JVM testing helper). */
    public boolean sameDay(Date a, Date b) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(a)
                .equals(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(b));
    }
}
