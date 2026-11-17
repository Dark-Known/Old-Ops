package service;

import static org.junit.Assert.*;

import org.junit.Test;
import service.TaskInputValidator.Input;

public class TaskInputValidatorTest {

    private static Input transfer() {
        Input in = new Input();
        in.name = "Nightly";
        in.taskType = "FILE_TRANSFER";
        in.targetHost = "files.example.com";
        in.targetUser = "ops";
        in.targetPass = "pw";
        in.sourcePath = "C:\\out";
        in.targetFolder = "/in";
        in.transferMode = "ENTIRE_FOLDER";
        return in;
    }

    private static Input mail() {
        Input in = new Input();
        in.name = "Mail";
        in.taskType = "OUTLOOK_MAIL";
        in.mailbox = "a@b.c"; in.tenantId = "common"; in.clientId = "cid"; in.mailFolder = "Inbox";
        in.outputFolder = "C:\\rcv"; in.searchCriteria = "subject:invoice"; in.maxResults = "50";
        return in;
    }

    private static Input watch() {
        Input in = new Input();
        in.name = "Watch";
        in.taskType = "FOLDER_WATCH";
        in.watchPath = "/in"; in.watchNew = true;
        return in;
    }

    private static Input backup() {
        Input in = new Input();
        in.name = "Backup";
        in.taskType = "BACKUP";
        in.backupSourcePath = "/a"; in.backupDestinationPath = "/b";
        return in;
    }

    // ── shared ───────────────────────────────────────────────────────────────

    @Test public void validInputsOfEveryTypeHaveNoProblem() {
        assertNull(TaskInputValidator.firstProblem(transfer()));
        assertNull(TaskInputValidator.firstProblem(mail()));
        assertNull(TaskInputValidator.firstProblem(watch()));
        assertNull(TaskInputValidator.firstProblem(backup()));
    }

    @Test public void aNameIsAlwaysRequiredAndCheckedFirst() {
        Input in = transfer();
        in.name = "   ";
        in.targetHost = "";
        assertEquals("Task name is required.", TaskInputValidator.firstProblem(in));
    }

    // ── file transfer ────────────────────────────────────────────────────────

    @Test public void aFileTransferNeedsAServerAndLogin() {
        Input in = transfer();
        in.targetHost = " ";
        assertEquals("Target hostname / IP is required.", TaskInputValidator.firstProblem(in));
        in = transfer(); in.targetUser = "";
        assertEquals("Target username is required.", TaskInputValidator.firstProblem(in));
        in = transfer(); in.targetPass = "";
        assertEquals("Target password is required.", TaskInputValidator.firstProblem(in));
    }

    @Test public void aPasswordOfOnlySpacesCounts() {
        Input in = transfer();
        in.targetPass = "  ";
        assertNull("passwords are not trimmed", TaskInputValidator.firstProblem(in));
    }

    @Test public void aFileTransferNeedsPaths() {
        Input in = transfer();
        in.sourcePath = "";
        assertEquals("Source path is required.", TaskInputValidator.firstProblem(in));
        in = transfer(); in.targetFolder = " ";
        assertEquals("Destination folder is required.", TaskInputValidator.firstProblem(in));
    }

    @Test public void aSpecificFileNeedsAFilenameNotAFolder() {
        Input in = transfer();
        in.transferMode = "SPECIFIC_FILE";
        in.targetFolder = "/remote/dir/";
        assertTrue(TaskInputValidator.firstProblem(in).contains("must include a filename"));
        in.targetFolder = "C:\\dir\\";
        assertTrue(TaskInputValidator.firstProblem(in).contains("must include a filename"));
        in.targetFolder = "incoming";
        assertTrue(TaskInputValidator.firstProblem(in).contains("looks like a folder name"));
        in.targetFolder = "/remote/dir/file.txt";
        assertNull(TaskInputValidator.firstProblem(in));
        in.targetFolder = "/remote/dir/file";
        assertNull("a path with a separator is accepted", TaskInputValidator.firstProblem(in));
    }

    @Test public void theFilenameRuleOnlyAppliesToSpecificFileMode() {
        Input in = transfer();
        in.targetFolder = "/remote/dir/";
        assertNull(TaskInputValidator.firstProblem(in));
    }

    // ── mail ─────────────────────────────────────────────────────────────────

    @Test public void mailDoesNotNeedAServerLogin() {
        Input in = mail();
        in.targetHost = ""; in.targetUser = ""; in.targetPass = "";
        assertNull(TaskInputValidator.firstProblem(in));
    }

    @Test public void mailNeedsItsRequiredFieldsInOrder() {
        Input in = mail(); in.mailbox = "";
        assertEquals("Mailbox address is required.", TaskInputValidator.firstProblem(in));
        in = mail(); in.tenantId = "";
        assertTrue(TaskInputValidator.firstProblem(in).startsWith("Azure AD Tenant ID is required"));
        in = mail(); in.clientId = "";
        assertTrue(TaskInputValidator.firstProblem(in).startsWith("Azure AD Client ID is required"));
        in = mail(); in.mailFolder = "";
        assertEquals("Mail folder is required.", TaskInputValidator.firstProblem(in));
        in = mail(); in.outputFolder = "";
        assertTrue(TaskInputValidator.firstProblem(in).startsWith("Output Folder is required"));
        in = mail(); in.searchCriteria = " ";
        assertEquals("Search criteria is required.", TaskInputValidator.firstProblem(in));
    }

    @Test public void maxMessagesMustBeAWholeNumberFromOneTo5000() {
        Input in = mail();
        in.maxResults = "abc";
        assertEquals("Max Messages must be a whole number.", TaskInputValidator.firstProblem(in));
        in.maxResults = "";
        assertEquals("Max Messages must be a whole number.", TaskInputValidator.firstProblem(in));
        in.maxResults = "0";
        assertEquals("Max Messages must be between 1 and 5000.", TaskInputValidator.firstProblem(in));
        in.maxResults = "5001";
        assertEquals("Max Messages must be between 1 and 5000.", TaskInputValidator.firstProblem(in));
        in.maxResults = " 5000 ";
        assertNull(TaskInputValidator.firstProblem(in));
        in.maxResults = "1";
        assertNull(TaskInputValidator.firstProblem(in));
    }

    // ── folder watch ─────────────────────────────────────────────────────────

    @Test public void aWatchNeedsAFolderAndSomethingToReport() {
        Input in = watch(); in.watchPath = "";
        assertEquals("The folder to watch is required.", TaskInputValidator.firstProblem(in));
        in = watch(); in.watchNew = false; in.watchChanged = false;
        assertEquals("Choose what to report: new files, changed files, or both.", TaskInputValidator.firstProblem(in));
        in.watchChanged = true;
        assertNull(TaskInputValidator.firstProblem(in));
    }

    @Test public void aRemoteWatchNeedsACredentialName() {
        Input in = watch();
        in.watchRemote = true;
        assertTrue(TaskInputValidator.firstProblem(in).startsWith("Credential (the name of a saved credential)"));
        in.watchCredential = "srv";
        assertNull(TaskInputValidator.firstProblem(in));
    }

    // ── backup ───────────────────────────────────────────────────────────────

    @Test public void aBackupNeedsBothFolders() {
        Input in = backup(); in.backupSourcePath = "";
        assertEquals("Backup source folder is required.", TaskInputValidator.firstProblem(in));
        in = backup(); in.backupDestinationPath = "";
        assertEquals("Backup destination folder is required.", TaskInputValidator.firstProblem(in));
    }

    @Test public void aBackupCannotBeRemoteOnBothSides() {
        Input in = backup();
        in.backupSourceRemote = true; in.backupDestinationRemote = true;
        in.backupSourceCredential = "a"; in.backupDestinationCredential = "b";
        assertTrue(TaskInputValidator.firstProblem(in).contains("cannot both be remote"));
    }

    @Test public void aRemoteBackupSideNeedsItsCredential() {
        Input in = backup(); in.backupSourceRemote = true;
        assertTrue(TaskInputValidator.firstProblem(in).startsWith("Source Credential"));
        in.backupSourceCredential = "srv";
        assertNull(TaskInputValidator.firstProblem(in));
        in = backup(); in.backupDestinationRemote = true;
        assertTrue(TaskInputValidator.firstProblem(in).startsWith("Destination Credential"));
    }

    @Test public void anUnknownTypeOnlyChecksTheCommonRules() {
        Input in = new Input();
        in.name = "x"; in.taskType = "SOMETHING_NEW";
        in.targetHost = "h"; in.targetUser = "u"; in.targetPass = "p";
        assertNull(TaskInputValidator.firstProblem(in));
    }
}
