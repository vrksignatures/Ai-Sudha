# On-device smoke checklist

Run this once on the actual Oppo/Vivo phone before letting the bot loose on a
live retailer list. It takes ~10 minutes and it is the only way to confirm the
parts that cannot be unit tested (the target app's tree, the panel, gestures).

## 1. Install & permissions

- [ ] `adb install -r app-debug.apk` (or open the APK on the phone)
- [ ] `Settings → Accessibility → Downloaded services → SudhaAutoBot → On`
- [ ] `Settings → Apps → Special access → Display over other apps → SudhaAutoBot → Allow`
- [ ] MainActivity shows `ACCESSIBILITY ✓ enabled` and `OVERLAY ✓ allowed`
- [ ] Target package saved (`com.vrksignatures.sudhadairy`)

## 2. Floating panel

- [ ] **3 · Show floating panel** → panel appears, does not steal input from the target app
- [ ] Dragging by the header moves it, buttons still work after a drag
- [ ] `▾` collapses the body, `▸` restores it
- [ ] `✕` hides the panel
- [ ] Counters read `0 / 0 / 0` and `Retailers 0/125 · next: —`

## 3. Step 1–3: navigation + filtering

- [ ] Open Sudha Daily Orders, sit on the home screen
- [ ] Press **▶ START** → the panel says `Home · finding Customers`, then `Customer list`
- [ ] With the list **collapsed**, the panel shows a small SMR count and taps
      **Show Retailers** by itself (watch the list expand)
- [ ] `Retailers x/125` climbs; no row with `SM65`-style or plain numeric id is
      ever opened (check the app stays on the list between SMRs)
- [ ] Press **⏸ PAUSE** mid-list → nothing is tapped for 30 s
- [ ] Press **▶ START** → it resumes without re-opening a finished retailer

## 4. Step 4–5: the order form + the 3-day rule

- [ ] A retailer opens → `Milk` → the `+` FAB → `Copy Order`
- [ ] The panel names the date it is trying (`T-1 = …`)
- [ ] For a retailer that *did* order yesterday: quantities appear and it says
      `Placing the order`
- [ ] For a retailer that ordered nothing for 3 days: ledger row becomes
      `SKIPPED_NO_DATA`, the app returns to the list on its own
- [ ] The calendar is never paged past the current month (test near month end)

## 5. Step 6: placement

- [ ] `PLACE ORDER` → whatever payment/confirm screens appear are handled
- [ ] Success screen → ledger row `SUCCESS`, panel `SUCCESS` +1
- [ ] It returns to the customer list and continues with the next SMR
- [ ] `Reset` clears today's ledger and stops

## 6. Battery / interruption

- [ ] Lock the screen mid-run → bot pauses; unlock + START resumes
- [ ] Kill the app from recents → relaunch → the ledger still shows today's rows
- [ ] Change the phone date to tomorrow → ledger rotates to a new empty day

If any step fails, capture `adb logcat -s SudhaBot SudhaPanel SudhaLedger` and
the panel's message line; both name the state that was active.
