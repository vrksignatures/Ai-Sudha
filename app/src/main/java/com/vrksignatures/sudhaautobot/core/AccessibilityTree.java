package com.vrksignatures.sudhaautobot.core;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Defensive helper around {@link AccessibilityNodeInfo}.
 *
 * <p>Third-party trees are hostile: recycled nodes, {@code NullPointerException}
 * from {@code getText()} on freshly detached rows, nodes that refuse
 * {@code ACTION_CLICK}, identical labels in several windows. Everything here
 * therefore:</p>
 *
 * <ul>
 *   <li>never assumes a node is non-null,</li>
 *   <li>always tries the cheapest click first and falls back to a geometry
 *       tap ({@code GestureDescription}) when the app swallows
 *       {@code ACTION_CLICK},</li>
 *   <li>walks at most {@link #MAX_DEPTH} levels so a runaway tree cannot hang
 *       the engine,</li>
 *   <li>returns fresh lists (callers may hold them without risking
 *       concurrent modification).</li>
 * </ul>
 */
public final class AccessibilityTree {

    /** Hard limits that keep one screen parse in the millisecond range. */
    public static final int MAX_DEPTH = 32;
    public static final int MAX_NODES = 4000;

    private final AccessibilityService service;

    public AccessibilityTree(AccessibilityService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------
    // Raw roots
    // ------------------------------------------------------------------

    /** @return the active window root, or null when the service is not ready. */
    public AccessibilityNodeInfo activeRoot() {
        return service.getRootInActiveWindow();
    }

    /**
     * All window roots, oldest first. Dialogs (date picker, "no data" alert,
     * payment sheet) often live in their own window and are invisible from the
     * active root, so screen recognition must look here too when the active root
     * has no match.
     */
    public List<AccessibilityNodeInfo> windowRoots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> windows = service.getWindows();
            for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) {
                    out.add(root);
                }
            }
        } catch (RuntimeException e) {
            // Some vendor ROMs throw here while a window is animating.
            AccessibilityNodeInfo root = activeRoot();
            if (root != null) {
                out.add(root);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Traversal
    // ------------------------------------------------------------------

    /** Depth-first list of every node below {@code root}. */
    public List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root) {
        if (root == null) {
            return Collections.emptyList();
        }
        List<AccessibilityNodeInfo> out = new ArrayList<>(128);
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        stack.push(root);
        depths.push(0);
        while (!stack.isEmpty() && out.size() < MAX_NODES) {
            AccessibilityNodeInfo node = stack.pop();
            Integer depth = depths.pop();
            if (node == null) {
                continue;
            }
            out.add(node);
            if (depth == null || depth >= MAX_DEPTH) {
                continue;
            }
            int childCount;
            try {
                childCount = node.getChildCount();
            } catch (RuntimeException e) {
                continue;
            }
            for (int i = childCount - 1; i >= 0; i--) {
                try {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        stack.push(child);
                        depths.push(depth + 1);
                    }
                } catch (RuntimeException ignored) {
                    // node recycled mid-walk
                }
            }
        }
        return out;
    }

    /** Every non-empty text/contentDescription/viewId label on the screen. */
    public List<String> allLabels(AccessibilityNodeInfo root) {
        List<String> out = new ArrayList<>(64);
        for (AccessibilityNodeInfo n : flatten(root)) {
            String t = labelOf(n);
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** @return text, else contentDescription, else hint — never null. */
    public String labelOf(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        CharSequence text = safeText(node);
        if (text != null && text.length() > 0) {
            return text.toString().trim();
        }
        CharSequence desc = safeContentDescription(node);
        if (desc != null && desc.length() > 0) {
            return desc.toString().trim();
        }
        CharSequence hint = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                hint = node.getHintText();
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
        if (hint != null && hint.length() > 0) {
            return hint.toString().trim();
        }
        return "";
    }

    private CharSequence safeText(AccessibilityNodeInfo node) {
        try {
            return node.getText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private CharSequence safeContentDescription(AccessibilityNodeInfo node) {
        try {
            return node.getContentDescription();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /** First node whose label equals any of {@code labels} (case-insensitive). */
    public AccessibilityNodeInfo findByTexts(AccessibilityNodeInfo root, String... labels) {
        if (root == null || labels == null || labels.length == 0) {
            return null;
        }
        for (AccessibilityNodeInfo n : flatten(root)) {
            if (matchesAnyLabel(n, labels)) {
                return n;
            }
        }
        return null;
    }

    /** First clickable-ish node whose label equals any of {@code labels}. */
    public AccessibilityNodeInfo findClickableByTexts(AccessibilityNodeInfo root, String... labels) {
        AccessibilityNodeInfo loose = null;
        for (AccessibilityNodeInfo n : flatten(root)) {
            if (matchesAnyLabel(n, labels)) {
                AccessibilityNodeInfo clickable = closestClickable(n);
                if (clickable != null) {
                    return clickable;
                }
                if (loose == null) {
                    loose = n;
                }
            }
        }
        return loose;
    }

    /** First node whose label contains any of {@code needles} (case-insensitive). */
    public AccessibilityNodeInfo findByTextContains(AccessibilityNodeInfo root, String... needles) {
        if (root == null || needles == null) {
            return null;
        }
        for (AccessibilityNodeInfo n : flatten(root)) {
            String label = labelOf(n).toLowerCase(Locale.ROOT);
            if (label.isEmpty()) {
                continue;
            }
            for (String needle : needles) {
                if (needle != null && label.contains(needle.toLowerCase(Locale.ROOT))) {
                    return n;
                }
            }
        }
        return null;
    }

    public String viewIdOf(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        try {
            CharSequence id = node.getViewIdResourceName();
            return id == null ? "" : id.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private boolean matchesAnyLabel(AccessibilityNodeInfo node, String... labels) {
        String label = labelOf(node);
        if (label.isEmpty()) {
            return false;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        for (String want : labels) {
            if (want == null) {
                continue;
            }
            String w = want.toLowerCase(Locale.ROOT).trim();
            if (lower.equals(w)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Clicking
    // ------------------------------------------------------------------

    /**
     * Clicks a node the way a human would, in the cheapest order that works:
     * <ol>
     *   <li>{@code ACTION_CLICK} on the node,</li>
     *   <li>{@code ACTION_CLICK} on its nearest clickable ancestor,</li>
     *   <li>a real gesture tap in the middle of its bounds.</li>
     * </ol>
     *
     * @return true when some strategy reported success.
     */
    public boolean click(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (performClick(node)) {
            return true;
        }
        AccessibilityNodeInfo clickable = closestClickable(node);
        if (clickable != null && clickable != node && performClick(clickable)) {
            return true;
        }
        return tapCenter(node);
    }

    /** Plain {@code ACTION_CLICK}, no fallbacks. */
    public boolean performClick(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            if (!node.isClickable()) {
                return false;
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Forces a click even on nodes that claim not to be clickable. Used for the
     * "Show Retailers" row, whose checkbox does not advertise itself.
     */
    public boolean forceClick(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        if (tapCenter(node)) {
            return true;
        }
        AccessibilityNodeInfo parent = safeParent(node);
        return parent != null && performClick(parent);
    }

    /** Gesture tap at the centre of {@code node}. */
    public boolean tapCenter(AccessibilityNodeInfo node) {
        Rect r = boundsOf(node);
        if (r == null || r.width() <= 0 || r.height() <= 0) {
            return false;
        }
        return tap(r.centerX(), r.centerY());
    }

    /** Gesture tap at absolute screen coordinates. */
    public boolean tap(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false; // gestures need API 24+
        }
        try {
            android.accessibilityservice.GestureDescription.Builder b =
                    new android.accessibilityservice.GestureDescription.Builder();
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(Math.max(0, x), Math.max(0, y));
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60));
            return service.dispatchGesture(b.build(), null, null);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Gesture swipe, used when a list exposes no scroll action. */
    public boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
        try {
            android.accessibilityservice.GestureDescription.Builder b =
                    new android.accessibilityservice.GestureDescription.Builder();
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, Math.max(80, durationMs)));
            return service.dispatchGesture(b.build(), null, null);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @return the node itself when clickable, else the nearest clickable ancestor. */
    public AccessibilityNodeInfo closestClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        int guard = 0;
        while (cur != null && guard++ < 12) {
            try {
                if (cur.isClickable()) {
                    return cur;
                }
            } catch (RuntimeException e) {
                return null;
            }
            cur = safeParent(cur);
        }
        return null;
    }

    public AccessibilityNodeInfo safeParent(AccessibilityNodeInfo node) {
        try {
            return node == null ? null : node.getParent();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Scrolling
    // ------------------------------------------------------------------

    /** {@code ACTION_SCROLL_FORWARD} on the first scrollable node. */
    public boolean scrollForward() {
        for (AccessibilityNodeInfo root : windowRoots()) {
            for (AccessibilityNodeInfo n : flatten(root)) {
                if (isScrollable(n)) {
                    try {
                        if (n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                            return true;
                        }
                    } catch (RuntimeException ignored) {
                        // try next
                    }
                }
            }
            // No scrollable node (Flutter/Compose lists often hide it): swipe up.
            Rect screen = screenBounds();
            int x = screen.centerX();
            return swipe(x, (int) (screen.height() * 0.72f), x, (int) (screen.height() * 0.32f), 320);
        }
        return false;
    }

    public boolean isScrollable(AccessibilityNodeInfo node) {
        try {
            return node != null && node.isScrollable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /** @return screen-space bounds, or null when unavailable. */
    public Rect boundsOf(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) {
                return null;
            }
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean isVisible(AccessibilityNodeInfo node) {
        Rect r = boundsOf(node);
        if (r == null) {
            return false;
        }
        try {
            if (!node.isVisibleToUser()) {
                return false;
            }
        } catch (RuntimeException ignored) {
            // trust bounds
        }
        return Rect.intersects(r, screenBounds());
    }

    public Rect screenBounds() {
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    // ------------------------------------------------------------------
    // Row grouping
    // ------------------------------------------------------------------

    /** @return the package of the active window, "" when unknown. */
    public String foregroundPackage() {
        AccessibilityNodeInfo root = activeRoot();
        if (root == null) {
            return "";
        }
        try {
            CharSequence pkg = root.getPackageName();
            return pkg == null ? "" : pkg.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Sends a back press to the service (kept here so only one class does it). */
    public boolean pressBack() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }
}
