package com.vrksignatures.sudhaautobot.core;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-day JSON journal of every retailer the bot has touched.
 *
 * <p>Why a file and not SharedPreferences: the ledger is written on every state
 * transition (125 retailers × ~6 writes), and a crash mid-write must never
 * corrupt yesterday's record. Writes go to a temp file and are renamed over the
 * live file, which is atomic on the same filesystem.</p>
 *
 * <p>File: {@code getFilesDir()/ledger/ledger-YYYY-MM-DD.json}</p>
 *
 * <pre>
 * {
 *   "date": "2026-11-17",
 *   "createdAt": 1763373600000,
 *   "updatedAt": 1763380112000,
 *   "entries": {
 *     "SMR42": { "status": "SUCCESS", "attempts": 1, "dateTried": "2026-11-16", "at": 1763380112000, "note": "order placed" },
 *     "SMR7":  { "status": "SKIPPED_NO_DATA", "attempts": 3, "dateTried": "2026-11-14", "at": 1763380000000, "note": "T-1..T-3 empty" }
 *   }
 * }
 * </pre>
 *
 * <p>An instance is bound to one calendar day. {@link #rolloverIfNeeded()} is
 * called by the engine before every decision; when the clock crosses midnight
 * the ledger is flushed and a fresh day starts, so a bot that has been sitting
 * on a phone for days can never re-process yesterday's retailers as today's.</p>
 */
public final class DailyLedger {

    private static final String TAG = "SudhaLedger";
    private static final String DIRECTORY = "ledger";
    private static final String FILE_PREFIX = "ledger-";
    private static final String FILE_SUFFIX = ".json";
    private static final int SCHEMA_VERSION = 1;

    /** A single retailer record. Mutable only through {@link DailyLedger}. */
    public static final class Entry {
        private final String smrId;
        private LedgerStatus status;
        private int attempts;
        private String dateTried;
        private long at;
        private String note;

        Entry(String smrId) {
            this.smrId = smrId;
            this.status = LedgerStatus.IN_PROGRESS;
            this.attempts = 0;
            this.dateTried = "";
            this.at = 0L;
            this.note = "";
        }

        public String getSmrId() {
            return smrId;
        }

        public LedgerStatus getStatus() {
            return status;
        }

        public int getAttempts() {
            return attempts;
        }

        /** The T-k date ("2026-11-16") that produced data, empty when none did. */
        public String getDateTried() {
            return dateTried == null ? "" : dateTried;
        }

        /** Epoch millis of the last update. */
        public long getTimestamp() {
            return at;
        }

        public String getNote() {
            return note == null ? "" : note;
        }

        public boolean isStale(long now, long staleMs) {
            return status == LedgerStatus.IN_PROGRESS && (now - at) > staleMs;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("status", status.name());
            o.put("attempts", attempts);
            o.put("dateTried", getDateTried());
            o.put("at", at);
            o.put("note", getNote());
            return o;
        }

        static Entry fromJson(String smrId, JSONObject o) {
            Entry e = new Entry(smrId);
            String raw = o.optString("status", LedgerStatus.IN_PROGRESS.name());
            LedgerStatus parsed;
            try {
                parsed = LedgerStatus.valueOf(raw);
            } catch (IllegalArgumentException ex) {
                parsed = LedgerStatus.FAILED;
            }
            e.status = parsed;
            e.attempts = o.optInt("attempts", 0);
            e.dateTried = o.optString("dateTried", "");
            e.at = o.optLong("at", 0L);
            e.note = o.optString("note", "");
            return e;
        }
    }

    private final File directory;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private String dayKey;
    private long createdAt;
    private long updatedAt;

    private DailyLedger(File directory, String dayKey) {
        this.directory = directory;
        this.dayKey = dayKey;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Opens (or creates) the ledger for the device's current day.
     *
     * @param source the app's private storage ({@code context::getFilesDir})
     */
    public static DailyLedger open(FilesDirProvider source) {
        return open(source.getFilesDir());
    }

    /**
     * Opens (or creates) the ledger for the device's current day.
     *
     * @param filesDir usually {@code context.getFilesDir()}
     */
    public static DailyLedger open(File filesDir) {
        File dir = new File(filesDir, DIRECTORY);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create ledger dir, keeping in memory only: " + dir);
        }
        DailyLedger ledger = new DailyLedger(dir, today());
        ledger.load();
        return ledger;
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private File fileFor(String key) {
        return new File(directory, FILE_PREFIX + key + FILE_SUFFIX);
    }

    private synchronized void load() {
        File file = fileFor(dayKey);
        if (!file.exists()) {
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 4L * 1024L * 1024L)];
            int read = in.read(buf);
            if (read <= 0) {
                return;
            }
            JSONObject root = new JSONObject(new String(buf, 0, read, StandardCharsets.UTF_8));
            createdAt = root.optLong("createdAt", createdAt);
            updatedAt = root.optLong("updatedAt", updatedAt);
            JSONObject items = root.optJSONObject("entries");
            if (items != null) {
                Iterator<String> it = items.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    JSONObject o = items.optJSONObject(id);
                    if (o != null) {
                        entries.put(id, Entry.fromJson(id, o));
                    }
                }
            }
            Log.i(TAG, "loaded " + entries.size() + " entries for " + dayKey);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "ledger unreadable, starting a fresh one for " + dayKey, e);
            entries.clear();
        }
    }

    /**
     * Writes the ledger to disk. Safe to call from the engine thread; the write
     * happens on a caller-supplied executor by the engine, this method itself
     * is synchronous but small (a few kB).
     */
    public synchronized boolean flush() {
        JSONObject root = new JSONObject();
        JSONObject items = new JSONObject();
        try {
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                items.put(e.getKey(), e.getValue().toJson());
            }
            root.put("schema", SCHEMA_VERSION);
            root.put("date", dayKey);
            root.put("createdAt", createdAt);
            updatedAt = System.currentTimeMillis();
            root.put("updatedAt", updatedAt);
            root.put("entries", items);

            File target = fileFor(dayKey);
            File tmp = new File(directory, target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                // Some FUSE-backed /data mounts refuse rename over an existing
                // file; fall back to a direct write rather than losing data.
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return true;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "failed to flush ledger", e);
            return false;
        }
    }

    /**
     * Ensures the ledger matches the device clock.
     *
     * @return true when the day changed and a new empty ledger was started.
     */
    public synchronized boolean rolloverIfNeeded() {
        String now = today();
        if (now.equals(dayKey)) {
            return false;
        }
        Log.i(TAG, "day rolled over " + dayKey + " -> " + now);
        flush();
        entries.clear();
        dayKey = now;
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
        flush();
        return true;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public synchronized String getDayKey() {
        return dayKey;
    }

    public synchronized Entry get(String smrId) {
        return entries.get(RetailerPolicy.canonical(smrId));
    }

    public synchronized LedgerStatus statusOf(String smrId) {
        Entry e = get(smrId);
        return e == null ? null : e.getStatus();
    }

    /** Read-only snapshot for the floating panel. */
    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    public synchronized int count(LedgerStatus status) {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.status == status) {
                n++;
            }
        }
        return n;
    }

    /** SUCCESS + every explicit skip = rows we are done with today. */
    public synchronized int countResolved() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.status.isFinalForDay()) {
                n++;
            }
        }
        return n;
    }

    public synchronized int countTouched() {
        return entries.size();
    }

    public synchronized int countErrors() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.status.isError()) {
                n++;
            }
        }
        return n;
    }

    public synchronized int countSkipped() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.status.isSkipped()) {
                n++;
            }
        }
        return n;
    }

    public synchronized int countSuccess() {
        return count(LedgerStatus.SUCCESS);
    }

    /**
     * Ids that still need work: not final, and either never tried, stale, or
     * below the retry limit. Used by the panel's "next" readout.
     */
    public synchronized List<String> pendingIds() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (isPending(e, now)) {
                out.add(e.smrId);
            }
        }
        Collections.sort(out, RetailerPolicy::numericOrder);
        return out;
    }

    /** @return true when this ledger row may be (re)processed now. */
    public synchronized boolean isPending(String smrId) {
        Entry e = get(smrId);
        return e != null && isPending(e, System.currentTimeMillis());
    }

    private boolean isPending(Entry e, long now) {
        if (e.status.isFinalForDay()) {
            return false;
        }
        if (e.status == LedgerStatus.IN_PROGRESS) {
            return e.isStale(now, RetailerPolicy.IN_PROGRESS_STALE_MS);
        }
        return e.attempts < RetailerPolicy.MAX_ATTEMPTS;
    }

    /**
     * The set of ids the engine should not click, for O(1) lookups while
     * scanning the screen. Rebuilt on demand; the entry map is tiny.
     */
    public synchronized java.util.Set<String> finalIds() {
        java.util.HashSet<String> out = new java.util.HashSet<>(entries.size() * 2);
        for (Entry e : entries.values()) {
            if (e.status.isFinalForDay()) {
                out.add(e.smrId);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** Marks a retailer as being processed right now (increments attempts). */
    public synchronized Entry markInProgress(String smrId, String note) {
        Entry e = ensure(smrId);
        e.status = LedgerStatus.IN_PROGRESS;
        e.attempts = e.attempts + 1;
        e.at = System.currentTimeMillis();
        e.note = note == null ? "" : note;
        flush();
        return e;
    }

    /** Order placed; success screen seen. Final for the day. */
    public synchronized Entry markSuccess(String smrId, String dateTried, String note) {
        Entry e = ensure(smrId);
        e.status = LedgerStatus.SUCCESS;
        e.dateTried = dateTried == null ? "" : dateTried;
        e.at = System.currentTimeMillis();
        e.note = note == null ? "" : note;
        flush();
        return e;
    }

    /** No copy-order data on T-1..T-3. Final for the day. */
    public synchronized Entry markSkippedNoData(String smrId, String lastDateTried, String note) {
        Entry e = ensure(smrId);
        e.status = LedgerStatus.SKIPPED_NO_DATA;
        e.dateTried = lastDateTried == null ? "" : lastDateTried;
        e.at = System.currentTimeMillis();
        e.note = note == null ? "" : note;
        flush();
        return e;
    }

    /** Deliberately ignored retailer. Final for the day. */
    public synchronized Entry markSkippedExcluded(String smrId, String reason) {
        Entry e = ensure(smrId);
        e.status = LedgerStatus.SKIPPED_EXCLUDED;
        e.at = System.currentTimeMillis();
        e.note = reason == null ? "" : reason;
        flush();
        return e;
    }

    /** Error; stays retryable until {@link RetailerPolicy#MAX_ATTEMPTS}. */
    public synchronized Entry markFailed(String smrId, String error) {
        Entry e = ensure(smrId);
        e.status = LedgerStatus.FAILED;
        e.at = System.currentTimeMillis();
        e.note = error == null ? "" : error;
        flush();
        return e;
    }

    /**
     * Operator pressed Reset: wipes today's file so the full list is walked
     * again. Kept as a separate method (rather than deleting from outside) so
     * the file handle and the in-memory map can never diverge.
     */
    public synchronized void resetToday() {
        entries.clear();
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
        File f = fileFor(dayKey);
        // Remove first, then make sure no half-deleted state can be reloaded:
        // a missing file is a valid "nothing done today" ledger.
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "could not delete " + f + ", truncating instead");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.e(TAG, "could not truncate " + f, e);
            }
        }
        Log.i(TAG, "ledger reset for " + dayKey);
    }

    /** Removes a single row (used when a retailer turns out to be mis-parsed). */
    public synchronized void forget(String smrId) {
        if (entries.remove(RetailerPolicy.canonical(smrId)) != null) {
            flush();
        }
    }

    private Entry ensure(String smrId) {
        String id = RetailerPolicy.canonical(smrId);
        Entry e = entries.get(id);
        if (e == null) {
            e = new Entry(id);
            entries.put(id, e);
        }
        return e;
    }
}
