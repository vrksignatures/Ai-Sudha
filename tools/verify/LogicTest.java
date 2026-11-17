import com.vrksignatures.sudhaautobot.core.*;
import java.util.*;

public class LogicTest {
    static int pass = 0, fail = 0;
    static void check(String what, Object actual, Object expected) {
        boolean ok = Objects.equals(String.valueOf(actual), String.valueOf(expected));
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what + " -> got '" + actual + "' want '" + expected + "'"); }
    }

    public static void main(String[] args) {
        // ---- RetailerPolicy: strict SMR matching ----
        check("SMR0042", RetailerPolicy.extractSmrId("SMR0042"), "SMR42");
        check("smr 12", RetailerPolicy.extractSmrId("smr 12"), "SMR12");
        check("SMR-7", RetailerPolicy.extractSmrId("SMR-7"), "SMR7");
        check("row label", RetailerPolicy.extractSmrId("SMR0042 · Ramesh Kumar · 2.5 L"), "SMR42");
        check("glued", RetailerPolicy.extractSmrId("SMR0042·Ramesh"), "SMR42");
        check("SM65 ignored", RetailerPolicy.extractSmrId("SM65"), null);
        check("65 ignored", RetailerPolicy.extractSmrId("65"), null);
        check("AMR0012 ignored", RetailerPolicy.extractSmrId("AMR0012"), null);
        check("ASMR1 ignored", RetailerPolicy.extractSmrId("ASMR1"), null);
        check("SMRX ignored", RetailerPolicy.extractSmrId("SMRX1"), null);
        check("distributor SM65", RetailerPolicy.isDistributorOrNumeric("SM65"), true);
        check("distributor 65", RetailerPolicy.isDistributorOrNumeric("65"), true);
        check("distributor DM12", RetailerPolicy.isDistributorOrNumeric("DM12"), true);
        check("2.5 not numeric-id", RetailerPolicy.isDistributorOrNumeric("2.5"), false);
        check("numeric order", RetailerPolicy.numericOrder("SMR2", "SMR10") < 0, true);
        check("collect", RetailerPolicy.collectRetailerIds(Arrays.asList("SMR9","SM65","65","SMR9","SMR10")), "[SMR9, SMR10]");
        // expand rules
        check("expand when 2 rows", RetailerPolicy.shouldExpandList(2, 2, true), true);
        check("expand when no SMR", RetailerPolicy.shouldExpandList(40, 0, true), true);
        check("no expand when full", RetailerPolicy.shouldExpandList(125, 125, true), false);
        check("no checkbox -> no expand", RetailerPolicy.shouldExpandList(2, 2, false), false);

        // ---- DateRollbackPolicy: strict T-1..T-3, current month only ----
        Calendar fixed = Calendar.getInstance();
        fixed.set(2026, Calendar.NOVEMBER, 17, 12, 0, 0);
        fixed.set(Calendar.MILLISECOND, 0);
        DateRollbackPolicy p = new DateRollbackPolicy(fixed);
        check("labels", p.attemptLabels(), "[16, 15, 14]");
        check("ISO T-1", p.isoDateOffset(1), "2026-11-16");
        check("ISO T-3", p.isoDateOffset(3), "2026-11-14");
        check("display T-2", p.displayDateOffset(2), "Nov 15");
        check("month header", p.currentMonthHeader(), "November 2026");
        check("all same month", p.allAttemptsInCurrentMonth(), true);
        check("describe 0", p.describeAttempt(0), "T-1 = Nov 16");
        check("describe 2", p.describeAttempt(2), "T-3 = Nov 14");
        check("beyond T-3", p.offsetForAttempt(3), -1);
        check("exhausted at 3", p.exhausted(3), true);
        check("next(0)", p.nextAttempt(0), 1);
        check("next(2)", p.nextAttempt(2), -1);

        // month boundary: Nov 2 -> T-3 = Oct 30 -> must NOT be attempted
        Calendar early = Calendar.getInstance();
        early.set(2026, Calendar.NOVEMBER, 2, 12, 0, 0);
        DateRollbackPolicy q = new DateRollbackPolicy(early);
        check("legal offsets Nov 2", q.legalOffsets(), "[1]");
        check("T-3 not same month", q.isSameMonthAsToday(3), false);
        check("not all in month", q.allAttemptsInCurrentMonth(), false);
        check("next(0) at boundary", q.nextAttempt(0), -1);

        // March 1 in a non-leap year
        Calendar mar1 = Calendar.getInstance();
        mar1.set(2027, Calendar.MARCH, 1, 12, 0, 0);
        DateRollbackPolicy r = new DateRollbackPolicy(mar1);
        check("legal offsets Mar 1 (all in Feb)", r.legalOffsets(), "[]");
        check("Mar 1 next(0)", r.nextAttempt(0), -1);
        check("Mar 1 T-1 is Feb", r.isoDateOffset(1), "2027-02-28");

        // a mid-month day where T-2 leaves the month
        Calendar nov2 = Calendar.getInstance();
        nov2.set(2026, Calendar.NOVEMBER, 2, 12, 0, 0);
        check("Nov 2 labels", new DateRollbackPolicy(nov2).attemptLabels(), "[1, 31, 30]");
        check("Nov 2 legal", new DateRollbackPolicy(nov2).legalOffsets(), "[1]");
        check("Nov 2 next(0)", new DateRollbackPolicy(nov2).nextAttempt(0), -1);

        System.out.println("\npassed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }
}
