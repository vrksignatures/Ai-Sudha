package com.vrksignatures.sudhaautobot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Which piece of text on this row is a retailer we may click?"
 *
 * <p>The customer list mixes three kinds of identifiers:</p>
 *
 * <pre>
 *   SMR0042        &lt;- retailer, the only thing we may click
 *   SM65           &lt;- distributor, never touch
 *   65             &lt;- plain number (route/sequence), never touch
 *   AMR0012        &lt;- another retailer family, not ours
 * </pre>
 *
 * <p>Everything in this class is pure: no Android types, no I/O, so the rules
 * can be reasoned about (and unit tested) without a device.</p>
 */
public final class RetailerPolicy {

    /** Canonical retailer id: the letters SMR followed by digits. */
    public static final String PREFIX = "SMR";

    /** Hard cap from the operator brief: a full retailer list is ~125 rows. */
    public static final int EXPECTED_TOTAL = 125;

    /** Below this many visible retailer rows the list is considered collapsed. */
    public static final int COLLAPSED_THRESHOLD = 2;

    /** Retries per retailer before it is written to the ledger as FAILED. */
    public static final int MAX_ATTEMPTS = 3;

    /** An IN_PROGRESS row older than this is treated as abandoned. */
    public static final long IN_PROGRESS_STALE_MS = 10L * 60L * 1000L;

    /**
     * Case-insensitive SMR match with boundaries, so "SMR0042" matches but
     * "SM65", "65", "SMRX1" or "ASMR1" do not. Boundary is expressed with
     * negative lookbehind/lookahead instead of \b so that it also works when the
     * id is glued to punctuation like "SMR0042·" or "SMR0042 (Ramesh)".
     */
    private static final Pattern SMR_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9])smr\\s*-?\\s*0*([0-9]{1,6})(?![0-9A-Za-z])",
                    Pattern.CASE_INSENSITIVE);

    /** A distributor / route code, used only to explain why a row was ignored. */
    private static final Pattern DISTRIBUTOR_PATTERN =
            Pattern.compile("^(SM|DM|DR|DS|ROUTE|DIST)[\\s-]*[0-9]+$", Pattern.CASE_INSENSITIVE);

    /** A row that is only a number (sequence number, quantity, amount). */
    private static final Pattern NUMERIC_ONLY = Pattern.compile("^[0-9]{1,6}$");

    private RetailerPolicy() {
    }

    /**
     * Extracts the canonical retailer id from a raw accessibility label.
     *
     * @param text any label, e.g. {@code "SMR0042 · Ramesh Kumar · 2.5 L"}.
     * @return the normalised id ({@code "SMR42"}), or {@code null} when the text
     *         is not a retailer id. Never returns a distributor id.
     */
    public static String extractSmrId(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = SMR_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        String digits = m.group(1);
        // Strip leading zeros by going through a number, then rebuild.
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        return PREFIX + value;
    }

    /**
     * True when a whole label looks like an id that must never be treated as a
     * retailer: "SM65", "DM12", "65", "2.5".
     */
    public static boolean isDistributorOrNumeric(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return false;
        }
        if (NUMERIC_ONLY.matcher(t).matches()) {
            return true;
        }
        return DISTRIBUTOR_PATTERN.matcher(t).matches();
    }

    /**
     * @return true when the label carries a clickable retailer id at all.
     */
    public static boolean containsRetailerId(String text) {
        return extractSmrId(text) != null;
    }

    /**
     * Finds every distinct retailer id in a set of labels, in the order they
     * appear on screen.
     *
     * @param labels ALL text labels of one screen (nulls allowed).
     * @return de-duplicated ids, empty when nothing matched.
     */
    public static List<String> collectRetailerIds(List<String> labels) {
        List<String> out = new ArrayList<>();
        if (labels == null) {
            return out;
        }
        for (String label : labels) {
            String id = extractSmrId(label);
            if (id != null && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Sorting helper so the list is processed top-to-bottom in numeric order
     * (SMR2 before SMR10) rather than lexicographically.
     */
    public static int numericOrder(String a, String b) {
        return Integer.compare(numericPart(a), numericPart(b));
    }

    /**
     * Parses the numeric part of an SMR id. Returns {@link Integer#MAX_VALUE}
     * when the id is malformed so broken rows sort last.
     */
    public static int numericPart(String smrId) {
        if (smrId == null) {
            return Integer.MAX_VALUE;
        }
        String digits = smrId.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Should the retailer list be considered collapsed and expanded?
     *
     * <p>The "Show Retailers" checkbox does not expose {@code isChecked()}, so
     * the only observable signal is the row count:</p>
     *
     * <ul>
     *   <li>2 or fewer visible retailer rows -> collapsed, tap the checkbox;</li>
     *   <li>no SMR row visible at all -> also tap it (the list may show only
     *       distributors in collapsed mode);</li>
     *   <li>otherwise leave the checkbox alone.</li>
     * </ul>
     *
     * @param visibleRetailerCount number of SMR rows currently on screen
     * @param visibleSmrOnScreen   number of SMR rows (same number in practice,
     *                             kept separate because a collapsed list shows
     *                             premium customers we must not count)
     * @param showRetailersPresent whether the "Show Retailers" label exists
     */
    public static boolean shouldExpandList(int visibleRetailerCount,
                                          int visibleSmrOnScreen,
                                          boolean showRetailersPresent) {
        if (!showRetailersPresent) {
            return false;
        }
        if (visibleRetailerCount <= COLLAPSED_THRESHOLD) {
            return true;
        }
        return visibleSmrOnScreen <= 0;
    }

    /**
     * @return the canonical form used inside the ledger.
     */
    public static String canonical(String smrId) {
        String id = extractSmrId(smrId);
        if (id != null) {
            return id;
        }
        return smrId == null ? "" : smrId.trim().toUpperCase(Locale.ROOT);
    }
}
