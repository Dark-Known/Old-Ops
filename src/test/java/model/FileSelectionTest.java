package model;

import model.FileSelection.MatchMode;
import model.FileSelection.Op;
import model.FileSelection.Pick;
import model.FileSelection.Rule;
import model.ScheduledTask.TransferMode;
import org.junit.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class FileSelectionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    // 2026-10-25 12:00:00 UTC
    private static final long NOW = 1_792_929_600_000L;
    private static final long MIN = 60_000L, HOUR = 3_600_000L, DAY = 86_400_000L;

    private static F f(String name, long size, long ageMillis) {
        return new F(name, size, NOW - ageMillis);
    }

    private static List<String> names(List<F> files) {
        List<String> n = new ArrayList<>();
        for (F x : files) n.add(x.name());
        return n;
    }

    private static boolean ok(FileSelection s, F x) {
        return s.filter(NOW, UTC).test(x.name(), x.size(), x.mtime());
    }

    private static FileSelection one(Op op, String value) {
        return new FileSelection().addRule(new Rule(false, op, value, true));
    }

    private static List<F> sample() {
        return Arrays.asList(
                f("a.txt", 300, 5 * HOUR), f("b.txt", 100, 1 * HOUR), f("c.txt", 200, 3 * HOUR),
                f("d.csv", 400, 2 * HOUR), f("e.txt", 100, 1 * HOUR));
    }

    // ── name conditions ──────────────────────────────────────────────

    private static List<F> run(FileSelection s, TransferMode m) {
        return s.select(sample(), F::name, F::size, F::mtime, m);
    }

    @Test
    public void endsWithStartsWithContainsEquals() {
        assertTrue(ok(one(Op.ENDS_WITH, ".csv"), f("a.CSV", 1, 0)));
        assertFalse(ok(one(Op.ENDS_WITH, ".csv"), f("a.csv.bak", 1, 0)));
        assertTrue(ok(one(Op.STARTS_WITH, "ldm_"), f("LDM_20261025.txt", 1, 0)));
        assertTrue(ok(one(Op.CONTAINS, "report"), f("daily_report_final.xlsx", 1, 0)));
        assertFalse(ok(one(Op.CONTAINS, "report"), f("summary.xlsx", 1, 0)));
        assertTrue(ok(one(Op.EQUALS, "data.txt"), f("data.txt", 1, 0)));
        assertFalse(ok(one(Op.EQUALS, "data.txt"), f("data.txt.old", 1, 0)));
    }

    @Test
    public void caseSensitivityIsPerCondition() {
        FileSelection s = new FileSelection().addRule(new Rule(false, Op.ENDS_WITH, ".CSV", false));
        assertTrue(ok(s, f("a.CSV", 1, 0)));
        assertFalse(ok(s, f("a.csv", 1, 0)));
    }

    @Test
    public void conditionsOnlyLookAtTheFileNameNotTheFolder() {
        assertTrue(ok(one(Op.STARTS_WITH, "data"), f("/home/ops/in/data1.txt", 1, 0)));
        assertFalse(ok(one(Op.CONTAINS, "ops"), f("/home/ops/in/data1.txt", 1, 0)));
        assertTrue(ok(one(Op.ENDS_WITH, ".txt"), f("C:\\in\\data1.txt", 1, 0)));
    }

    @Test
    public void wildcardMatchesWholeNameAndAllowsSeveralPatterns() {
        FileSelection s = one(Op.WILDCARD, "report_??.txt;*.csv");
        assertTrue(ok(s, f("report_01.txt", 1, 0)));
        assertTrue(ok(s, f("anything.csv", 1, 0)));
        assertFalse(ok(s, f("report_001.txt", 1, 0)));
        assertFalse(ok(s, f("xreport_01.txt", 1, 0)));
        assertFalse(ok(s, f("report_01.txt.bak", 1, 0)));
        // regex characters in a wildcard are literal
        assertTrue(ok(one(Op.WILDCARD, "a+b(1).txt"), f("a+b(1).txt", 1, 0)));
        assertFalse(ok(one(Op.WILDCARD, "a+b(1).txt"), f("aab1.txt", 1, 0)));
    }

    @Test
    public void regexMatchesAnywhereUnlessAnchored() {
        assertTrue(ok(one(Op.REGEX, "\\d{8}"), f("file_20261025_x.txt", 1, 0)));
        assertFalse(ok(one(Op.REGEX, "^\\d{8}$"), f("file_20261025_x.txt", 1, 0)));
        assertTrue(ok(one(Op.REGEX, "^\\d{8}\\.txt$"), f("20261025.txt", 1, 0)));
    }

    @Test
    public void extensionListAcceptsSeveralSeparatorsAndDots() {
        FileSelection s = one(Op.EXTENSION_IN, "csv, .TXT;log");
        assertTrue(ok(s, f("a.csv", 1, 0)));
        assertTrue(ok(s, f("a.txt", 1, 0)));
        assertTrue(ok(s, f("a.log", 1, 0)));
        assertFalse(ok(s, f("a.xlsx", 1, 0)));
        assertFalse(ok(s, f("csv", 1, 0))); // no extension at all
    }

    // ── combining conditions ─────────────────────────────────────────

    @Test
    public void invalidRegexNeverMatchesAndIsReported() {
        FileSelection s = one(Op.REGEX, "([unclosed");
        assertFalse(ok(s, f("a.txt", 1, 0)));
        assertEquals(1, s.validate().size());
    }

    @Test
    public void allVersusAnyOfTheIncludeConditions() {
        FileSelection s = new FileSelection()
                .addRule(new Rule(false, Op.STARTS_WITH, "ldm", true))
                .addRule(new Rule(false, Op.ENDS_WITH, ".txt", true));
        assertTrue(ok(s, f("ldm_1.txt", 1, 0)));
        assertFalse(ok(s, f("ldm_1.csv", 1, 0)));
        s.setMatchMode(MatchMode.ANY);
        assertTrue(ok(s, f("ldm_1.csv", 1, 0)));
        assertTrue(ok(s, f("other.txt", 1, 0)));
        assertFalse(ok(s, f("other.csv", 1, 0)));
    }

    @Test
    public void anExcludeConditionAlwaysWins() {
        FileSelection s = new FileSelection()
                .setMatchMode(MatchMode.ANY)
                .addRule(new Rule(false, Op.ENDS_WITH, ".txt", true))
                .addRule(new Rule(true, Op.CONTAINS, "draft", true));
        assertTrue(ok(s, f("final.txt", 1, 0)));
        assertFalse(ok(s, f("draft_final.txt", 1, 0)));
    }

    // ── date placeholders ────────────────────────────────────────────

    @Test
    public void onlyExcludeConditionsMeansEverythingElseQualifies() {
        FileSelection s = new FileSelection().addRule(new Rule(true, Op.ENDS_WITH, ".bak", true));
        assertTrue(ok(s, f("a.txt", 1, 0)));
        assertFalse(ok(s, f("a.bak", 1, 0)));
    }

    @Test
    public void datePlaceholdersExpandAtRunTime() {
        assertTrue(ok(one(Op.CONTAINS, "{today}"), f("rep_20261025.txt", 1, 0)));
        assertFalse(ok(one(Op.CONTAINS, "{today}"), f("rep_20261024.txt", 1, 0)));
        assertTrue(ok(one(Op.CONTAINS, "{yesterday}"), f("rep_20261024.txt", 1, 0)));
        assertTrue(ok(one(Op.CONTAINS, "{today:yyyy-MM-dd}"), f("rep_2026-10-25.txt", 1, 0)));
        assertTrue(ok(one(Op.ENDS_WITH, "{tomorrow:ddMMM}.txt"), f("x_26Oct.txt", 1, 0)));
        // a regex quantifier is not mistaken for a placeholder
        assertTrue(ok(one(Op.REGEX, "^\\d{8}$"), f("20261025", 1, 0)));
    }

    // ── size / age / temp ────────────────────────────────────────────

    @Test
    public void badDatePatternIsReported() {
        assertEquals(1, one(Op.CONTAINS, "{today:yyyy'}").validate().size());
    }

    @Test
    public void sizeLimits() {
        FileSelection s = new FileSelection().setMinSizeBytes(10).setMaxSizeBytes(100);
        assertFalse(ok(s, f("a", 9, 0)));
        assertTrue(ok(s, f("a", 10, 0)));
        assertTrue(ok(s, f("a", 100, 0)));
        assertFalse(ok(s, f("a", 101, 0)));
        assertEquals(1, new FileSelection().setMinSizeBytes(50).setMaxSizeBytes(5).validate().size());
    }

    @Test
    public void modifiedWithinAndSettledAndToday() {
        FileSelection within = new FileSelection().setModifiedWithinMinutes(60);
        assertTrue(ok(within, f("a", 1, 59 * MIN)));
        assertFalse(ok(within, f("a", 1, 61 * MIN)));

        FileSelection settled = new FileSelection().setSettledSeconds(30);
        assertFalse(ok(settled, f("a", 1, 10_000)));   // changed 10 s ago: probably still being written
        assertTrue(ok(settled, f("a", 1, 31_000)));

        FileSelection today = new FileSelection().setModifiedToday(true);
        assertTrue(ok(today, f("a", 1, 11 * HOUR)));    // 01:00 today
        assertFalse(ok(today, f("a", 1, 13 * HOUR)));   // 23:00 yesterday
    }

    // ── picking ──────────────────────────────────────────────────────

    @Test
    public void tempFilesAreSkippedOnRequest() {
        FileSelection s = new FileSelection().setSkipTempFiles(true);
        for (String n : Arrays.asList(".hidden", "~$doc.docx", "x.tmp", "big.zip.part", "y.crdownload", "z~", "Thumbs.db")) {
            assertFalse(n, ok(s, f(n, 1, 0)));
        }
        assertTrue(ok(s, f("real.txt", 1, 0)));
        assertTrue(ok(new FileSelection(), f(".hidden", 1, 0)));  // off by default
    }

    @Test
    public void noRulesAndByModeBehaveLikeTheOriginalModes() {
        FileSelection none = new FileSelection();
        assertFalse(none.isActive());
        assertEquals(5, run(none, TransferMode.ENTIRE_FOLDER).size());
        // LATEST_ONLY: the newest timestamp, with every file that shares it
        assertEquals(Arrays.asList("b.txt", "e.txt"), names(run(none, TransferMode.LATEST_ONLY)));
    }

    @Test
    public void latestOnlyAppliesToTheFilteredFilesNotToTheWholeFolder() {
        FileSelection s = one(Op.ENDS_WITH, ".csv");
        assertEquals(Arrays.asList("d.csv"), names(run(s, TransferMode.LATEST_ONLY)));
        assertEquals(Arrays.asList("d.csv"), names(run(s, TransferMode.ENTIRE_FOLDER)));
    }

    @Test
    public void newestOldestLargestSmallestWithCount() {
        assertEquals(Arrays.asList("b.txt", "e.txt"), names(run(new FileSelection().setPick(Pick.NEWEST).setPickCount(2), TransferMode.ENTIRE_FOLDER)));
        assertEquals(Arrays.asList("a.txt"), names(run(new FileSelection().setPick(Pick.OLDEST), TransferMode.ENTIRE_FOLDER)));
        assertEquals(Arrays.asList("d.csv", "a.txt"), names(run(new FileSelection().setPick(Pick.LARGEST).setPickCount(2), TransferMode.ENTIRE_FOLDER)));
        assertEquals(Arrays.asList("b.txt"), names(run(new FileSelection().setPick(Pick.SMALLEST), TransferMode.ENTIRE_FOLDER)));
    }

    @Test
    public void keepTiesAddsFilesSharingTheCutoffValue() {
        FileSelection s = new FileSelection().setPick(Pick.SMALLEST).setPickCount(1).setKeepTies(true);
        assertEquals(Arrays.asList("b.txt", "e.txt"), names(run(s, TransferMode.ENTIRE_FOLDER)));
    }

    @Test
    public void pickAllOverridesLatestOnlyMode() {
        assertEquals(5, run(new FileSelection().setPick(Pick.ALL), TransferMode.LATEST_ONLY).size());
    }

    @Test
    public void countLargerThanTheListIsFine() {
        assertEquals(5, run(new FileSelection().setPick(Pick.NEWEST).setPickCount(50), TransferMode.ENTIRE_FOLDER).size());
    }

    @Test
    public void emptyListGivesEmptyResult() {
        assertTrue(new FileSelection().setPick(Pick.NEWEST)
                .select(new ArrayList<F>(), F::name, F::size, F::mtime, TransferMode.ENTIRE_FOLDER).isEmpty());
    }

    @Test
    public void roundTripKeepsEverythingIncludingAwkwardText() {
        FileSelection s = new FileSelection()
                .setMatchMode(MatchMode.ANY)
                .addRule(new Rule(false, Op.WILDCARD, "a b|c=d;*.csv\u00e9", false))
                .addRule(new Rule(true, Op.REGEX, "^x\\d{2}$", true))
                .setMinSizeBytes(5).setMaxSizeBytes(9_000).setModifiedWithinMinutes(90).setSettledSeconds(20)
                .setModifiedToday(true).setSkipTempFiles(true)
                .setPick(Pick.LARGEST).setPickCount(3).setKeepTies(true).setFailIfNoMatch(true);
        FileSelection back = FileSelection.fromText(s.toText());
        assertEquals(s.toText(), back.toText());
        assertEquals(2, back.getRules().size());
        assertEquals("a b|c=d;*.csv\u00e9", back.getRules().get(0).value);
        assertFalse(back.getRules().get(0).ignoreCase);
        assertTrue(back.getRules().get(1).exclude);
        assertEquals(Pick.LARGEST, back.getPick());
        assertEquals(3, back.getPickCount());
        assertTrue(back.isFailIfNoMatch());
    }

    // ── storage ──────────────────────────────────────────────────────

    @Test
    public void blankOrDamagedTextMeansNoRules() {
        assertFalse(FileSelection.fromText(null).isActive());
        assertFalse(FileSelection.fromText("").isActive());
        assertFalse(FileSelection.fromText("garbage\n===\nrule=nonsense").isActive());
        assertEquals("", new FileSelection().toText());
    }

    @Test
    public void describeIsReadable() {
        FileSelection s = new FileSelection()
                .addRule(new Rule(false, Op.ENDS_WITH, ".csv", true))
                .addRule(new Rule(true, Op.CONTAINS, "tmp", true))
                .setSettledSeconds(30).setPick(Pick.NEWEST);
        String d = s.describe();
        assertTrue(d, d.contains("ends with \".csv\""));
        assertTrue(d, d.contains("NOT name contains \"tmp\""));
        assertTrue(d, d.contains("unchanged for 30s"));
        assertTrue(d, d.contains("newest 1"));
    }

    @Test
    public void sentPolicyAndAfterActionDefaultToTheOldBehaviour() {
        FileSelection s = new FileSelection();
        assertEquals(FileSelection.SentPolicy.ALWAYS, s.getSentPolicy());
        assertEquals(FileSelection.AfterAction.KEEP, s.getAfterAction());
        assertFalse(s.isActive());
        assertFalse(s.usesLedger());
        assertFalse(s.hasAfterAction());
    }

    // ── already-sent policy and after-transfer action ────────────────

    @Test
    public void skippingSentFilesChangesTheFileListButCleanupDoesNot() {
        assertTrue(new FileSelection().setSentPolicy(FileSelection.SentPolicy.UNCHANGED).isActive());
        assertTrue(new FileSelection().setSentPolicy(FileSelection.SentPolicy.ANY_NAME).usesLedger());
        FileSelection cleanup = new FileSelection().setAfterAction(FileSelection.AfterAction.DELETE);
        assertFalse(cleanup.isActive());
        assertTrue(cleanup.hasAfterAction());
    }

    @Test
    public void movingNeedsAFolder() {
        FileSelection s = new FileSelection().setAfterAction(FileSelection.AfterAction.MOVE);
        assertEquals(1, s.validate().size());
        s.setAfterFolder("  /archive/done  ");
        assertEquals("/archive/done", s.getAfterFolder());
        assertTrue(s.validate().isEmpty());
        assertTrue(new FileSelection().setAfterAction(FileSelection.AfterAction.DELETE).validate().isEmpty());
    }

    @Test
    public void sentPolicyAndAfterActionSurviveStorage() {
        FileSelection s = new FileSelection()
                .setSentPolicy(FileSelection.SentPolicy.ANY_NAME)
                .setAfterAction(FileSelection.AfterAction.MOVE)
                .setAfterFolder("D:\\Archive\\done files");
        FileSelection back = FileSelection.fromText(s.toText());
        assertEquals(FileSelection.SentPolicy.ANY_NAME, back.getSentPolicy());
        assertEquals(FileSelection.AfterAction.MOVE, back.getAfterAction());
        assertEquals("D:\\Archive\\done files", back.getAfterFolder());
        // cleanup alone must be stored too, even though it adds no file conditions
        FileSelection onlyCleanup = new FileSelection().setAfterAction(FileSelection.AfterAction.DELETE);
        assertEquals(FileSelection.AfterAction.DELETE, FileSelection.fromText(onlyCleanup.toText()).getAfterAction());
        // text written before these options existed still loads, with the old behaviour
        FileSelection old = FileSelection.fromText("v=1\nmatch=ALL\npick=NEWEST\ncount=2\n");
        assertEquals(FileSelection.SentPolicy.ALWAYS, old.getSentPolicy());
        assertEquals(FileSelection.AfterAction.KEEP, old.getAfterAction());
    }

    @Test
    public void describeMentionsSentPolicyAndCleanup() {
        String d = new FileSelection().setSentPolicy(FileSelection.SentPolicy.UNCHANGED)
                .setAfterAction(FileSelection.AfterAction.MOVE).setAfterFolder("/done").describe();
        assertTrue(d, d.contains("skip files already sent (unchanged)"));
        assertTrue(d, d.contains("then move the original to /done"));
        assertTrue(new FileSelection().setAfterAction(FileSelection.AfterAction.DELETE).describe().contains("then delete the original"));
    }

    @Test
    public void alertSettingsSurviveSavingAndDefaultToOff() {
        FileSelection s = new FileSelection().setAlertNoFiles(true).setAlertRepeatMinutes(15);
        FileSelection back = FileSelection.fromText(s.toText());
        assertTrue(back.isAlertNoFiles());
        assertEquals(15, back.getAlertRepeatMinutes());
        assertFalse("tasks saved before alerts existed do not alert", FileSelection.fromText("").isAlertNoFiles());
        assertFalse("an alert alone does not turn the file rules on", s.isActive());
        assertTrue(s.describe().contains("alert if no files"));
        assertEquals(0, new FileSelection().setAlertRepeatMinutes(-5).getAlertRepeatMinutes());
    }

    @Test
    public void silenceAlertSurvivesSavingAloneAndIsOffByDefault() {
        FileSelection s = new FileSelection().setSilenceMinutes(10);
        FileSelection back = FileSelection.fromText(s.toText());
        assertEquals(10, back.getSilenceMinutes());
        assertEquals(0, FileSelection.fromText("").getSilenceMinutes());
        assertFalse("does not turn the file rules on", s.isActive());
        assertTrue(s.describe().contains("alert if no file for 10 min"));
        assertEquals(0, new FileSelection().setSilenceMinutes(-3).getSilenceMinutes());
    }

    /**
     * A file in a listing: name, size in bytes, modified time as epoch ms.
     */
    private record F(String name, long size, long mtime) {
    }
}
