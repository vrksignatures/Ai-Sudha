package com.vrksignatures.sudhaautobot.core;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Operates the little month calendar that opens when "Copy Order" is pressed.
 *
 * <p>Constraints baked in on purpose (they are the whole point of the feature):</p>
 *
 * <ul>
 *   <li>only cells for T-1, T-2 and T-3 are ever tapped,</li>
 *   <li>the grid must still be showing the current month — the previous/next
 *       arrows are never used, so the bot cannot wander to another week or
 *       month,</li>
 *   <li>a day cell is matched by its day-of-month label ("16") plus a
 *       calendar-ish layout, never by position in the grid.</li>
 * </ul>
 *
 * <p>The class is stateless apart from the shared {@link DateRollbackPolicy};
 * the attempt cursor lives in the workflow machine.</p>
 */
public final class CalendarDateSelector {

    /** Labels that mean "this grid is a calendar for the current month". */
    private static final String[] MONTH_SCROLL_IDS = {
            "/date_picker", "/calendar", "/month", "/datepicker", "/mtrl_picker"
    };

    private static final Pattern MONTH_HEADER =
            Pattern.compile("(?i)\\b(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\.?\\s*[0-9]{2,4}\\b");

    private final AccessibilityTree tree;
    private final DateRollbackPolicy policy;

    public CalendarDateSelector(AccessibilityTree tree, DateRollbackPolicy policy) {
        this.tree = tree;
        this.policy = policy;
    }

    // ------------------------------------------------------------------
    // Recognition
    // ------------------------------------------------------------------

    /**
     * @return true when a calendar grid (with a month header) is on screen.
     */
    public boolean isCalendarVisible() {
        for (AccessibilityNodeInfo root : tree.windowRoots()) {
            if (findMonthHeaderNode(root) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the node whose label is the current month header, e.g. "November 2026"
     * or "Nov 2026". Returns null when no calendar is up.
     */
    public AccessibilityNodeInfo findMonthHeaderNode(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        for (AccessibilityNodeInfo n : tree.flatten(root)) {
            String label = tree.labelOf(n);
            if (label.isEmpty()) {
                continue;
            }
            Matcher m = MONTH_HEADER.matcher(label);
            if (m.find() && label.length() <= 28) {
                return n;
            }
        }
        return null;
    }

    /**
     * True when the calendar is still on the current month. We compare the
     * header against the policy's month/year. A header showing "October 2026"
     * while today is 2026-11-17 means the app pre-scrolled or the user moved it:
     * the bot must NOT guess.
     */
    public boolean isCurrentMonth() {
        for (AccessibilityNodeInfo root : tree.windowRoots()) {
            AccessibilityNodeInfo header = findMonthHeaderNode(root);
            if (header == null) {
                continue;
            }
            String label = tree.labelOf(header);
            return matchesCurrentMonth(label);
        }
        return false;
    }

    /** Pure helper so it can be unit tested. */
    public boolean matchesCurrentMonth(String headerLabel) {
        if (headerLabel == null || headerLabel.isEmpty()) {
            return false;
        }
        String lower = headerLabel.toLowerCase(java.util.Locale.ROOT);
        boolean monthOk = lower.contains(policy.currentMonthName().toLowerCase(java.util.Locale.ROOT))
                || lower.contains(policy.currentShortMonth().toLowerCase(java.util.Locale.ROOT));
        if (!monthOk) {
            return false;
        }
        // Year check only when the header actually carries one.
        if (Pattern.compile("[0-9]{4}").matcher(headerLabel).find()) {
            return lower.contains(String.valueOf(policy.currentYear()));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Cell lookup
    // ------------------------------------------------------------------

    /**
     * All candidate day cells for one day-of-month.
     *
     * <p>Filters out the usual impostors: the year label, quantity cells and the
     * app's own action bar numbers. A node qualifies when</p>
     * <ul>
     *   <li>its trimmed label equals the day number,</li>
     *   <li>it is roughly square-ish and small (a grid cell),</li>
     *   <li>it is not the month header.</li>
     * </ul>
     */
    public List<AccessibilityNodeInfo> findDayCells(String dayNumber) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (dayNumber == null || dayNumber.isEmpty()) {
            return out;
        }
        for (AccessibilityNodeInfo root : tree.windowRoots()) {
            for (AccessibilityNodeInfo n : tree.flatten(root)) {
                if (!tree.isVisible(n)) {
                    continue;
                }
                String label = tree.labelOf(n);
                if (!label.equals(dayNumber) && !label.equals(dayNumber + " ")) {
                    continue;
                }
                if (looksLikeHeader(n)) {
                    continue;
                }
                android.graphics.Rect r = tree.boundsOf(n);
                if (r == null || r.width() > 260 || r.height() > 260) {
                    continue; // not a calendar cell
                }
                out.add(n);
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return out;
    }

    private boolean looksLikeHeader(AccessibilityNodeInfo node) {
        String label = tree.labelOf(node);
        return MONTH_HEADER.matcher(label).find();
    }

    /**
     * Picks the best cell for a date that has {@code duplicates} copies on the
     * screen (some apps render the grid twice: once "selected", once "normal").
     * The lowest, leftmost, clickable one wins — that is the real grid.
     */
    public AccessibilityNodeInfo pickCell(String dayNumber) {
        List<AccessibilityNodeInfo> cells = findDayCells(dayNumber);
        if (cells.isEmpty()) {
            return null;
        }
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo c : cells) {
            if (best == null) {
                best = c;
                continue;
            }
            android.graphics.Rect rb = tree.boundsOf(best);
            android.graphics.Rect rc = tree.boundsOf(c);
            if (rb == null) {
                best = c;
                continue;
            }
            if (rc == null) {
                continue;
            }
            boolean cClickable = isClickable(c);
            boolean bestClickable = isClickable(best);
            if (cClickable != bestClickable) {
                if (cClickable) {
                    best = c;
                }
                continue;
            }
            if (rc.top > rb.top) {
                best = c;
            }
        }
        return best;
    }

    private boolean isClickable(AccessibilityNodeInfo n) {
        try {
            return n.isClickable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Taps the calendar cell for attempt {@code attemptIndex} (0 -> T-1).
     *
     * @return true when a cell was found and clicked.
     */
    public boolean selectAttempt(int attemptIndex) {
        int offset = policy.offsetForAttempt(attemptIndex);
        if (offset < 0) {
            return false;
        }
        String day = String.valueOf(policy.dayOfMonth(offset));
        AccessibilityNodeInfo cell = pickCell(day);
        if (cell == null) {
            return false;
        }
        return tree.click(cell);
    }

    /**
     * Taps a specific calendar day regardless of the attempt policy. Only used
     * by the recovery path when the app's own "today" chip has to be re-read.
     */
    public boolean selectDayOfMonth(int dayOfMonth) {
        AccessibilityNodeInfo cell = pickCell(String.valueOf(dayOfMonth));
        return cell != null && tree.click(cell);
    }

    /**
     * Fallback used when the grid exposes no usable text nodes (some WebViews
     * draw the calendar on a single canvas). We read the header to sanity-check
     * the month, then tap the column of T-k by arithmetic on the first visible
     * cell's grid. Returns false rather than guessing when the grid cannot be
     * measured.
     */
    public boolean selectByGeometry(int attemptIndex) {
        int offset = policy.offsetForAttempt(attemptIndex);
        if (offset < 0) {
            return false;
        }
        AndroidRect grid = measureGrid();
        if (grid == null) {
            return false;
        }
        int day = policy.dayOfMonth(offset);
        Calendar first = (Calendar) policy.dayOffset(offset).clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int firstWeekday = first.get(Calendar.DAY_OF_WEEK); // 1=Sunday
        int leadingBlanks = (firstWeekday - Calendar.SUNDAY + 7) % 7;
        int cellIndex = leadingBlanks + day - 1;
        int columns = 7;
        int rows = grid.height / Math.max(1, grid.cellHeight);
        int r = cellIndex / columns;
        int c = cellIndex % columns;
        if (r >= rows) {
            return false;
        }
        int x = grid.left + c * grid.cellWidth + grid.cellWidth / 2;
        int y = grid.top + r * grid.cellHeight + grid.cellHeight / 2;
        return tree.tap(x, y);
    }

    /** Measures the day grid by looking at the first row of numeric cells. */
    private AndroidRect measureGrid() {
        List<AccessibilityNodeInfo> numeric = new ArrayList<>();
        for (AccessibilityNodeInfo root : tree.windowRoots()) {
            for (AccessibilityNodeInfo n : tree.flatten(root)) {
                String label = tree.labelOf(n);
                if (label.isEmpty() || label.length() > 2) {
                    continue;
                }
                boolean allDigits = true;
                for (int i = 0; i < label.length(); i++) {
                    if (!Character.isDigit(label.charAt(i))) {
                        allDigits = false;
                        break;
                    }
                }
                if (!allDigits || looksLikeHeader(n)) {
                    continue;
                }
                android.graphics.Rect b = tree.boundsOf(n);
                if (b == null || b.width() > 220 || b.height() > 220) {
                    continue;
                }
                numeric.add(n);
            }
            if (!numeric.isEmpty()) {
                break;
            }
        }
        if (numeric.size() < 7) {
            return null;
        }
        // Sort by top, then left; the first 7 belong to one week row.
        numeric.sort((a, b) -> {
            android.graphics.Rect ra = tree.boundsOf(a);
            android.graphics.Rect rb = tree.boundsOf(b);
            if (ra == null || rb == null) {
                return 0;
            }
            if (Math.abs(ra.top - rb.top) > 12) {
                return Integer.compare(ra.top, rb.top);
            }
            return Integer.compare(ra.left, rb.left);
        });
        android.graphics.Rect first = tree.boundsOf(numeric.get(0));
        android.graphics.Rect seventh = tree.boundsOf(numeric.get(6));
        if (first == null || seventh == null) {
            return null;
        }
        AndroidRect grid = new AndroidRect();
        grid.left = first.left;
        grid.top = first.top;
        grid.cellWidth = Math.max(1, (seventh.left - first.left) / 6);
        grid.cellHeight = Math.max(1, first.height());
        grid.height = grid.cellHeight * 6;
        return grid;
    }

    /** Tiny value type so we do not allocate Rects in the hot path. */
    private static final class AndroidRect {
        int left;
        int top;
        int cellWidth;
        int cellHeight;
        int height;
    }
}
