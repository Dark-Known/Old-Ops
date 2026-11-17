package service;

/**
 * The wording for "the task / credential could not be saved". Two different failures both make a save return
 * false and need different messages: a dropped connection versus a healthy one where only this write failed
 * (lock contention with the Daemon, a constraint, a full disk). Saying the wrong one is misleading, e.g. telling
 * someone the database is not connected while the status bar correctly shows it is.
 */
public final class SaveFailureText {

    /** {@code activityDetail} goes to the activity log; {@code userMessage} is shown in the dialog. */
    public static final class Failure {
        public final String activityDetail;
        public final String userMessage;
        Failure(String activityDetail, String userMessage) {
            this.activityDetail = activityDetail;
            this.userMessage = userMessage;
        }
    }

    private SaveFailureText() { }

    private static String colon(String detail) { return detail != null ? ": " + detail : "."; }
    private static String colonNewline(String detail) { return detail != null ? ":\n" + detail : "."; }

    public static Failure credentialWriteFailed(String taskName, String detail) {
        return new Failure(
                "Could not save target credential for task \"" + taskName
                        + "\" — database connected, but this write failed" + colon(detail) + " Task was not saved.",
                "Could not save the target credential — the database is connected, but this write failed"
                        + colonNewline(detail)
                        + "\n\nThe task has NOT been saved. This is often transient — try again.");
    }

    public static Failure credentialNotConnected(String taskName, String detail) {
        return new Failure(
                "Could not save target credential for task \"" + taskName
                        + "\" — database not connected" + colon(detail) + " Task was not saved.",
                "Could not save the target credential — the database is not connected" + colonNewline(detail)
                        + "\n\nThe task has NOT been saved. Check the data directory / app.db and try again.");
    }

    public static Failure taskWriteFailed(String taskName, String detail) {
        return new Failure(
                "Could not save task \"" + taskName + "\" — database connected, but this write failed" + colon(detail),
                "Could not save this task — the database is connected, but this write failed" + colonNewline(detail)
                        + "\n\nYour changes have NOT been saved. This is often transient "
                        + "(e.g. the Daemon writing to app.db at the same moment) — try again.");
    }

    public static Failure taskNotConnected(String taskName, String detail) {
        return new Failure(
                "Could not save task \"" + taskName + "\" — database not connected" + colon(detail),
                "Could not save this task — the database is not connected" + colonNewline(detail)
                        + "\n\nYour changes have NOT been saved. Check the data directory / app.db and try again.");
    }

    /** Application settings could not be written because the settings table never opened. */
    public static Failure settingsNotConnected(String detail) {
        return new Failure(
                "Could not save application settings \u2014 database not connected" + colon(detail),
                "Could not save settings \u2014 the database is not connected"
                        + (detail != null ? ": " + detail : ".") + " Your changes were NOT saved.");
    }

    /** The WinSCP path could not be written (it is saved by its own call, after the other settings). */
    public static Failure winScpPathNotConnected(String detail) {
        return new Failure(
                "Could not save WinSCP path \u2014 database not connected" + colon(detail),
                "Could not save the WinSCP path \u2014 the database is not connected"
                        + (detail != null ? ": " + detail : "."));
    }
}
