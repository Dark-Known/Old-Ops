package service.schedule;

import model.ScheduledTask;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The "next run" text shown for a task. The time itself comes from {@link NextFire}, the same rule the
 * scheduler uses, so what the screen says is what will happen (for example a daily task that already
 * ran today shows tomorrow).
 */
public final class NextRunLabel {

    private NextRunLabel() { }

    public static String of(ScheduledTask t, LocalDateTime now, DateTimeFormatter format) {
        if (t.getStatus() == ScheduledTask.TaskStatus.DISABLED) return "Disabled";
        switch (t.getScheduleType()) {
            case RUN_NOW:
                return "On demand";
            case ONCE:
                if (t.getScheduledAt() == null) return "Invalid";
                if (t.getLastRunAt() == null && t.getScheduledAt().isBefore(now)) return "Overdue";
                return t.getScheduledAt().format(format);
            case DAILY:
            case WEEKLY: {
                Long delay = NextFire.delayMs(t, now);
                return delay == null ? "Invalid" : now.plusNanos(delay * 1_000_000L).format(format);
            }
            case INTERVAL_MINUTES:
                if (t.getIntervalMinutes() <= 0) return "Invalid";
                return (t.getLastRunAt() != null ? t.getLastRunAt().plusMinutes(t.getIntervalMinutes()) : now).format(format);
            case INTERVAL_SECONDS:
                if (t.getIntervalSeconds() <= 0) return "Invalid";
                return (t.getLastRunAt() != null ? t.getLastRunAt().plusSeconds(t.getIntervalSeconds()) : now).format(format);
            default:
                return "Unknown";
        }
    }
}
