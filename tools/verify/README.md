# Off-device verification harness

The bot's two riskiest rules are pure logic, so they are verified **without a
phone**. Both programs run on a plain JDK (no Gradle, no emulator) and exit
non-zero on failure.

| file | what it proves |
|---|---|
| `LogicTest.java` | `RetailerPolicy` + `DateRollbackPolicy`: SMR-only matching, distributor/numeric rejection, the strict T-1..T-3 window, month boundaries, zero-stripping, ordering — **42 assertions** |
| `ITest.java` | `DailyLedger` persistence (atomic write, reopen, corrupt-file recovery, retry/final semantics, reset) + `parseQuantity` — **22 assertions** |
| `WorkflowStateMachineAccess.java` | tiny bridge that exposes the package-private quantity parser to `ITest` |

## Running them

They need the Android classes the core touches (`AccessibilityNodeInfo`,
`Rect`, `Log`, `org.json`). Two options:

**A. Inside the Android project** — copy the three files into
`app/src/test/java/` and give them a `main()`, or call them from an
instrumented test. Everything they reference is already on the classpath.

**B. Plain JVM** — compile the three `core` classes that are Android-free
(`RetailerPolicy`, `DateRollbackPolicy`, `BotState`, `LedgerStatus`) together
with a stubbed `android.jar`, plus a real `org.json`:

```bash
javac -d out \
  app/src/main/java/com/vrksignatures/sudhaautobot/core/{RetailerPolicy,DateRollbackPolicy,BotState,LedgerStatus}.java \
  tools/verify/LogicTest.java
java -cp out LogicTest            # passed=42 failed=0
```

`ITest` additionally needs `DailyLedger`, `FilesDirProvider`, a stub
`android.view.accessibility.AccessibilityNodeInfo`, `android.graphics.Rect`,
`android.util.Log` and an `org.json` implementation.

## What is *not* covered here

Anything that needs a real screen: node-tree traversal quirks of the Sudha app,
the floating panel rendering, and the vendor gesture fallbacks. Those are
validated by `tools/verify/smoke-checklist.md` on a phone.
