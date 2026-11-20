package service;

/**
 * The rules a task must satisfy before it can be saved, independent of the screen that collects it.
 * {@link #firstProblem} returns the message for the first broken rule, in the order the person fills
 * the form in, or null when the input is acceptable. Rules that need a dialog (confirmations) or a
 * widget (the file-selection panel) stay with the screen.
 */
public final class TaskInputValidator {

    private TaskInputValidator() { }

    /** What the form holds, as plain text and flags. Unused fields for the chosen type may stay at their defaults. */
    public static final class Input {
        public String name = "";
        /** FILE_TRANSFER, OUTLOOK_MAIL, BACKUP or FOLDER_WATCH. */
        public String taskType = "";

        // file transfer: the server (every file transfer needs one; local to local is not supported)
        public String targetHost = "", targetUser = "", targetPass = "";
        public String sourcePath = "", targetFolder = "", transferMode = "";

        // mail
        public String mailbox = "", tenantId = "", clientId = "", mailFolder = "", outputFolder = "",
                searchCriteria = "", maxResults = "";

        // folder watch
        public String watchPath = "", watchCredential = "";
        public boolean watchRemote, watchNew, watchChanged;

        // backup
        public String backupSourcePath = "", backupDestinationPath = "", backupSourceCredential = "", backupDestinationCredential = "";
        public boolean backupSourceRemote, backupDestinationRemote;
    }

    /** @return the message for the first problem, or null if the input is acceptable */
    public static String firstProblem(Input in) {
        if (blank(in.name)) return "Task name is required.";

        boolean mail = "OUTLOOK_MAIL".equals(in.taskType);
        boolean backup = "BACKUP".equals(in.taskType);
        boolean watch = "FOLDER_WATCH".equals(in.taskType);
        boolean transfer = "FILE_TRANSFER".equals(in.taskType);

        if (!mail && !backup && !watch) {
            if (blank(in.targetHost)) return "Target hostname / IP is required.";
            if (blank(in.targetUser)) return "Target username is required.";
            if (in.targetPass == null || in.targetPass.isEmpty()) return "Target password is required.";
        }

        if (transfer) return transferProblem(in);
        if (mail) return mailProblem(in);
        if (watch) return watchProblem(in);
        if (backup) return backupProblem(in);
        return null;
    }

    private static String transferProblem(Input in) {
        if (blank(in.sourcePath)) return "Source path is required.";
        if (blank(in.targetFolder)) return "Destination folder is required.";
        if ("SPECIFIC_FILE".equals(in.transferMode)) {
            String folder = in.targetFolder.trim();
            if (folder.endsWith("/") || folder.endsWith("\\")) {
                return "For SPECIFIC_FILE mode, destination must include a filename "
                        + "(e.g., /remote/path/filename.txt), not just a folder path.";
            }
            if (!folder.contains(".") && !folder.contains("/") && !folder.contains("\\")) {
                return "For SPECIFIC_FILE mode, destination should include a filename. "
                        + "Current path looks like a folder name.";
            }
        }
        return null;
    }

    private static String mailProblem(Input in) {
        if (blank(in.mailbox)) return "Mailbox address is required.";
        if (blank(in.tenantId)) return "Azure AD Tenant ID is required (or \"common\").";
        if (blank(in.clientId)) return "Azure AD Client ID is required — see README “Outlook Mail setup”.";
        if (blank(in.mailFolder)) return "Mail folder is required.";
        if (blank(in.outputFolder)) return "Output Folder is required — each fetched message is written there as a .RCV file.";
        if (blank(in.searchCriteria)) return "Search criteria is required.";
        int max;
        try {
            max = Integer.parseInt(in.maxResults == null ? "" : in.maxResults.trim());
        } catch (NumberFormatException nfe) {
            return "Max Messages must be a whole number.";
        }
        if (max < 1 || max > 5000) return "Max Messages must be between 1 and 5000.";
        return null;
    }

    private static String watchProblem(Input in) {
        if (blank(in.watchPath)) return "The folder to watch is required.";
        if (in.watchRemote && blank(in.watchCredential)) {
            return "Credential (the name of a saved credential) is required when the folder is on a server.";
        }
        if (!in.watchNew && !in.watchChanged) return "Choose what to report: new files, changed files, or both.";
        return null;
    }

    private static String backupProblem(Input in) {
        if (blank(in.backupSourcePath)) return "Backup source folder is required.";
        if (blank(in.backupDestinationPath)) return "Backup destination folder is required.";
        if (in.backupSourceRemote && in.backupDestinationRemote) {
            return "Backup source and destination cannot both be remote — make one side local.";
        }
        if (in.backupSourceRemote && blank(in.backupSourceCredential)) {
            return "Source Credential (the name of a saved credential) is required when 'Source is remote' is checked.";
        }
        if (in.backupDestinationRemote && blank(in.backupDestinationCredential)) {
            return "Destination Credential (the name of a saved credential) is required when 'Destination is remote' is checked.";
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
