# SudhaAutoBot

An on-device Android accessibility macro that automates the daily order-entry
grind in **Sudha Daily Orders** — *Customers → SMR retailer → Milk → + →
Copy Order (T-1…T-3) → PLACE ORDER → payment chain* — for up to ~125 retailers,
with pause/resume safety.

No internet permission. No analytics. No third-party libraries: plain
`android.jar` framework APIs only.

---

## Build the APK on GitHub

1. Push this repository to a **new GitHub repo**, committing to `main`.
2. **Actions → Build APK** runs automatically.
3. Download **`app-debug.apk`** from the run's *Artifacts* section.

That APK is signed with the standard Android debug key, so it installs directly
(`adb install app-debug.apk` or copy it to the phone). The workflow also tries
to produce `app-release-unsigned.apk` for your own signing.

Locally: `./gradlew :app:assembleDebug`.

## On the phone (Oppo / Vivo notes)

1. `Settings → Accessibility → Downloaded services → SudhaAutoBot → On`
2. `Settings → Apps → Special access → Display over other apps → SudhaAutoBot → Allow`
3. Open the app, type the target package (`com.vrksignatures.sudhadairy`),
   press **Save package**.
4. Open Sudha Daily Orders, sign in, stay on the home screen.
5. In SudhaAutoBot press **3 · Show floating panel**, then **▶ START** on the panel.

Keep the panel pinned (ColorOS/Funtouch: *Lock in recents*, and disable
battery optimisation for this app).

## How the bot decides

| Step | Rule |
|---|---|
| Home | Find and tap **Customers** |
| Retailer list | The "Show Retailers" checkbox hides its checked state, so the bot counts rows instead: **≤ 2 visible retailers, or no SMR visible → tap "Show Retailers"** (max 3 taps, 5 s cool-down) |
| Filtering | Only `SMR[0-9]+` is a retailer. `SM65`, `65`, `AMR0012` are **never** tapped |
| Next retailer | Skip anything already `SUCCESS` / `SKIPPED_*` in today's ledger; scroll for more when all visible rows are settled |
| Order form | `Milk` → `+` (FAB) → `Copy Order` |
| Dates | **Strictly T-1, then T-2, then T-3.** No week jumps, no month changes. A date in the previous month is refused rather than paged to |
| Verdict | Quantities > 0 → place the order. Nothing after T-3 → `SKIPPED_NO_DATA`, cancel, next retailer |
| Placement | `PLACE ORDER` → `PAY` / `PROCEED` / `CONFIRM`, one press per tick |
| Success | `Done`/`Success`/`Order placed` → `SUCCESS`, `Done` or BACK to the list, re-expand, continue |

## The ledger

`getFilesDir()/ledger/ledger-YYYY-MM-DD.json` (atomic temp-file + rename):

```json
{
  "date": "2026-11-17",
  "entries": {
    "SMR42": { "status": "SUCCESS", "attempts": 1, "dateTried": "2026-11-16" },
    "SMR7":  { "status": "SKIPPED_NO_DATA", "attempts": 3, "dateTried": "2026-11-14" }
  }
}
```

`SUCCESS`, `SKIPPED_NO_DATA` and `SKIPPED_EXCLUDED` are final for the day.
`IN_PROGRESS` entries older than 10 minutes and `FAILED` entries under 3
attempts are retried. A new day rotates to a new file automatically, even if the
bot was left running overnight.

## Source layout

```
app/src/main/java/com/vrksignatures/sudhaautobot/
├── MainActivity.java              operator console (permissions, package, panel)
├── SudhaAccessibilityService.java the only accessibility consumer, tick loop
├── core/
│   ├── AccessibilityTree.java     crash-proof node search / click / gesture
│   ├── WorkflowStateMachine.java  the brain: screen → next action
│   ├── BotState.java              State + Sub enums
│   ├── DailyLedger.java           atomic per-day JSON ledger
│   ├── LedgerStatus.java          SUCCESS / SKIPPED_* / IN_PROGRESS / FAILED
│   ├── DateRollbackPolicy.java    the strict 3-day rule
│   ├── CalendarDateSelector.java  tapping the right day cell, never paging
│   ├── RetailerPolicy.java        SMR regex, distributor rejection, row counting
│   └── FilesDirProvider.java      storage seam (keeps the ledger testable on a JDK)
└── ui/FloatingPanelController.java  draggable panel: ▶ / ⏸ / ↺ + counters

tools/verify/
├── LogicTest.java              42 assertions: SMR matching + the 3-day window
├── ITest.java                  22 assertions: ledger persistence + quantities
└── smoke-checklist.md          the 10-minute on-phone checklist
```

## Verified before shipping

Both rule engines were run on a plain JDK, not on a phone:

```
LogicTest       passed=42 failed=0     SMR0042 -> SMR42, SM65/65/AMR0012 rejected,
                                       T-1..T-3 only, month boundary refused
ITest           passed=22 failed=0     atomic writes, reopen, corrupt-file recovery,
                                       SUCCESS/SKIPPED final, FAILED retried, reset
```

Everything else (tree quirks of the real app, the panel, vendor gestures) is
covered by `tools/verify/smoke-checklist.md`.

## Ground rules this bot follows

* It acts **only** while the configured package is in the foreground.
* It never types, never enters credentials, never leaves the app.
* Every tap is throttled; the payment chain presses at most one button per tick.
* Accessibility is a powerful permission — read every tap this code makes
  before you enable it on a phone that has real money on it.
