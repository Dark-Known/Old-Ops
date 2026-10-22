package ui.monitor;

import static org.junit.Assert.*;

import org.junit.Test;

public class TaskChangeViewTest {

    static final String CREATED = "Created task \"Daily invoices\" \u2014 Type: FILE_TRANSFER; Direction: INBOUND; "
            + "Transfer mode: LATEST_ONLY; Target credential: opsuser@sftp.example.com; Source path: D:\\Inbound\\Invoices; "
            + "Target path: /out/invoices; Schedule type: INTERVAL_MINUTES; Interval (minutes): 5; Watcher enabled: Yes; "
            + "Watcher fallback poll interval (min): 10; Watcher max file age (min): 60; Retry count: 3.";

    @Test
    public void createdTaskIsExplainedInPlainWords() {
        assertTrue(TaskChangeView.applies(CREATED));
        TaskChangeView.View v = TaskChangeView.render(CREATED, "m");
        String text = v.text();
        assertTrue(text, text.contains("In short: Downloads the newest file from opsuser@sftp.example.com (/out/invoices) "
                + "to D:\\Inbound\\Invoices, as soon as new files appear, every 5 minutes."));
        assertTrue(text, text.contains("Direction: Download \u2014 from the remote server to this PC"));
        assertTrue(text, text.contains("Files are taken from (remote folder): /out/invoices"));
        assertTrue(text, text.contains("Files are saved to (this PC): D:\\Inbound\\Invoices"));
        assertTrue(text, text.contains("If it fails: 3 retries after a failure"));
        assertFalse("raw codes must not leak", text.contains("INBOUND") || text.contains("LATEST_ONLY") || text.contains("FILE_TRANSFER"));
    }

    @Test
    public void uploadReadsTheOtherWayRound() {
        String m = "Created task \"Push\" \u2014 Type: FILE_TRANSFER; Direction: OUTBOUND; Transfer mode: ENTIRE_FOLDER; "
                + "Target credential: u@h; Source path: C:\\out; Target path: /in; Schedule type: DAILY.";
        String text = TaskChangeView.render(m, "m").text();
        assertTrue(text, text.contains("In short: Uploads every file from C:\\out to u@h (/in), every day."));
        assertTrue(text, text.contains("Files are sent to (remote folder): /in"));
        assertTrue(text, text.contains("Files are taken from (this PC): C:\\out"));
    }

    @Test
    public void editShowsBeforeAndAfter() {
        String m = "Edited task \"Daily invoices\" \u2014 Interval (minutes): 5 \u2192 15; Watcher enabled: Yes \u2192 No; "
                + "Target path: /out/invoices \u2192 /out/new; Transfer mode: LATEST_ONLY \u2192 ENTIRE_FOLDER";
        String text = TaskChangeView.render(m, "m").text();
        assertTrue(text, text.contains("Interval (minutes): 5 minutes -> 15 minutes"));
        assertTrue(text, text.contains("Watcher enabled: Yes -> No"));
        assertTrue(text, text.contains("Target path: /out/invoices -> /out/new"));
        assertTrue(text, text.contains("Transfer mode: Only the newest file -> Every file in the folder"));
    }

    @Test
    public void editWithNothingChangedAndSemicolonInValue() {
        assertTrue(TaskChangeView.render("Edited task \"X\" \u2014 no fields actually changed (re-saved as-is)", "m").text()
                .contains("No setting changed."));
        String m = "Edited task \"X\" \u2014 Target path: /a;b \u2192 /c; Retry count: 1 \u2192 2";
        String text = TaskChangeView.render(m, "m").text();
        assertTrue(text, text.contains("Target path: /a;b -> /c"));
        assertTrue(text, text.contains("Retry count: 1 retry after a failure -> 2 retries after a failure"));
    }

    @Test
    public void deletedTaskKeepsFinalConfiguration() {
        String m = "Deleted task \"Old\" \u2014 final configuration: Type: BACKUP; Backup source path: D:\\data; "
                + "Backup destination path: E:\\bk; Backup retention (days): 30; Schedule type: WEEKLY.";
        String text = TaskChangeView.render(m, "m").text();
        assertTrue(text, text.startsWith("Task deleted"));
        assertTrue(text, text.contains("In short: Backs up D:\\data to E:\\bk, every week."));
        assertTrue(text, text.contains("Keep backups for: 30 days"));
    }

    @Test
    public void mailTaskAndHtmlEscaping() {
        String m = "Created task \"Mail <in>\" \u2014 Type: OUTLOOK_MAIL; Mailbox address: ops@contoso.com; IMAP folder: INBOX; "
                + "Mail fetch scope: ALL_MATCHING; Mail max results: 50; Mail output folder: D:\\mail; Mark mail as read: Yes; "
                + "Schedule type: INTERVAL_SECONDS; Interval (seconds): 30.";
        TaskChangeView.View v = TaskChangeView.render(m, "m");
        assertTrue(v.text(), v.text().contains("In short: Reads mail from ops@contoso.com and saves it to D:\\mail, every 30 seconds."));
        assertTrue(v.text(), v.text().contains("How many: All matching messages"));
        assertFalse("values are escaped for HTML", v.html().contains("<in>"));
    }

    @Test
    public void otherMessagesAreLeftAlone() {
        assertFalse(TaskChangeView.applies("Manually queued \"X\" for immediate execution."));
        assertFalse(TaskChangeView.applies(null));
    }
}
