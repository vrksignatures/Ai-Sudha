import android.view.accessibility.AccessibilityNodeInfo;
import com.vrksignatures.sudhaautobot.core.*;
import java.io.File;
import java.util.*;

public class ITest {
    static int pass=0, fail=0;
    static void check(String what, boolean ok){
        if(ok){pass++; System.out.println("  ok   " + what);} else {fail++; System.out.println("  FAIL " + what);}
    }

    static AccessibilityNodeInfo row(String smr, int top){
        AccessibilityNodeInfo r = AccessibilityNodeInfo.make(null).clickable(true).at(0, top, 1000, top+90);
        r.add(AccessibilityNodeInfo.make(smr).at(10, top+10, 200, top+40));
        r.add(AccessibilityNodeInfo.make(smr + " · Ramesh").at(10, top+45, 600, top+80));
        return r;
    }
    static AccessibilityNodeInfo listScreen(String pkg, String... smrs){
        AccessibilityNodeInfo root = AccessibilityNodeInfo.make(null).pkg(pkg);
        root.add(AccessibilityNodeInfo.make("Daily Orders").at(0,0,500,60));
        root.add(AccessibilityNodeInfo.make("Show Retailers").clickable(true).at(700,100,1000,160));
        int top = 200;
        for (String s : smrs) { root.add(row(s, top)); top += 100; }
        return root;
    }

    public static void main(String[] a) throws Exception {
        System.out.println("== RetailerPolicy against a mixed screen ==");
        List<String> labels = Arrays.asList("SMR0001","SMR29","SM65","65","AMR0012","SMR0001 · x","2.5");
        check("only SMR ids collected", RetailerPolicy.collectRetailerIds(labels).equals(Arrays.asList("SMR1","SMR29")));

        System.out.println("== DailyLedger persistence ==");
        File dir = new File("/tmp/itest/data"); deleteRec(dir); dir.mkdirs();
        DailyLedger l1 = DailyLedger.open(dir);
        l1.markInProgress("SMR1","start");
        l1.markSuccess("SMR1","2026-11-16","ok");
        l1.markSkippedNoData("SMR7","2026-11-14","empty");
        l1.markFailed("SMR9","boom");
        check("file written", new File(dir,"ledger/ledger-"+l1.getDayKey()+".json").exists());
        check("success counted", l1.countSuccess()==1);
        check("skipped counted", l1.countSkipped()==1);
        check("errors counted", l1.countErrors()==1);
        check("resolved = success+skips", l1.countResolved()==2);
        DailyLedger l2 = DailyLedger.open(dir);
        check("reopened keeps SMR1", l2.statusOf("SMR1")==LedgerStatus.SUCCESS);
        check("reopened keeps SMR7 date", "2026-11-14".equals(l2.get("SMR7").getDateTried()));
        check("final ids", l2.finalIds().containsAll(Arrays.asList("SMR1","SMR7")) && !l2.finalIds().contains("SMR9"));
        check("failed is retryable", l2.isPending("SMR9"));
        check("success is not retryable", !l2.isPending("SMR1"));
        check("canonical lookup SMR0001", l2.statusOf("SMR0001")==LedgerStatus.SUCCESS);
        // corrupt file recovery
        File f = new File(dir,"ledger/ledger-"+l2.getDayKey()+".json");
        java.nio.file.Files.write(f.toPath(), "{not json".getBytes());
        DailyLedger l3 = DailyLedger.open(dir);
        check("corrupt ledger starts clean", l3.countTouched()==0);
        l3.markSuccess("SMR5","2026-11-16","ok");
        check("writable after corruption", DailyLedger.open(dir).statusOf("SMR5")==LedgerStatus.SUCCESS);
        l3.resetToday();
        check("reset clears", l3.countTouched()==0 && !f.exists());

        System.out.println("== parseQuantity (shared decision for 'did the copy work?') ==");
        check("2 -> 2", eq(WorkflowStateMachineAccess.parse("2"), 2.0));
        check("1.5 L -> 1.5", eq(WorkflowStateMachineAccess.parse("1.5 L"), 1.5));
        check("500 ml -> 500", eq(WorkflowStateMachineAccess.parse("500 ml"), 500.0));
        check("3 pkt -> 3", eq(WorkflowStateMachineAccess.parse("3 pkt"), 3.0));
        check("0 -> 0", eq(WorkflowStateMachineAccess.parse("0"), 0.0));
        check("SMR42 -> null", WorkflowStateMachineAccess.parse("SMR42")==null);
        check("Ramesh -> null", WorkflowStateMachineAccess.parse("Ramesh")==null);

        System.out.println("\npassed=" + pass + " failed=" + fail);
        if (fail>0) System.exit(1);
    }
    static boolean eq(Double d, double v){ return d!=null && Math.abs(d-v)<1e-9; }
    static void deleteRec(File f){ File[] c=f.listFiles(); if(c!=null) for(File x:c) deleteRec(x); f.delete(); }
}
