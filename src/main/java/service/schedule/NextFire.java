package service.schedule;

import model.ScheduledTask;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * When a scheduled task is next due. Pure date arithmetic: it reads the task's schedule fields and the
 * clock value it is given, and touches nothing else, so it is tested with fixed times.
 */
public final class NextFire {

    /** An event is delivered at its target instant or a few ms after; this much lateness still counts as "due now". */
    public static final long DUE_TOLERANCE_MS = 5_000L;

    private NextFire() { }

    /**
     * Milliseconds from {@code now} until the task's next occurrence, or {@code null} if it has none to
     * schedule: 0 when it is due right now, never negative.
     */
    public static Long delayMs(ScheduledTask task, LocalDateTime now) {
        switch (task.getScheduleType()) {
            case RUN_NOW:
                return task.getLastRunAt() == null ? 0L : null;

            case ONCE:
                if (task.getLastRunAt() != null || task.getScheduledAt() == null) return null;
                return millisUntil(task.getScheduledAt(), now);

            case DAILY: {
                if (task.getCronExpression() == null) return null;
                LocalTime target;
                try {
                    target = LocalTime.parse(task.getCronExpression(), DateTimeFormatter.ofPattern("HH:mm"));
                } catch (Exception e) {
                    return null;
                }
                LocalDateTime next = now.toLocalDate().atTime(target);
                if (justDue(next, now, task.getLastRunAt())) return 0L;
                boolean alreadyRanToday = task.getLastRunAt() != null
                        && !task.getLastRunAt().toLocalDate().isBefore(now.toLocalDate());
                if (!next.isAfter(now) || alreadyRanToday) {
                    next = next.plusDays(1);
                }
                return millisUntil(next, now);
            }

            case WEEKLY: {
                if (task.getCronExpression() == null) return null;
                String[] parts = task.getCronExpression().split(" ");
                if (parts.length < 2) return null;
                DayOfWeek targetDay = parseDayOfWeek(parts[0]);
                if (targetDay == null) return null;
                LocalTime target;
                try {
                    target = LocalTime.parse(parts[1], DateTimeFormatter.ofPattern("HH:mm"));
                } catch (Exception e) {
                    return null;
                }
                LocalDateTime next = now.toLocalDate().atTime(target);
                if (next.getDayOfWeek() == targetDay && justDue(next, now, task.getLastRunAt())) return 0L;
                // Walk forward to the next matching day/time that's actually
                // still ahead of now; bounded loop (max 7 steps) so a bad/
                // unmatched day name can never spin forever.
                for (int i = 0; i < 8 && (next.getDayOfWeek() != targetDay || !next.isAfter(now)); i++) {
                    next = next.plusDays(1).toLocalDate().atTime(target);
                }
                return millisUntil(next, now);
            }

            case INTERVAL_MINUTES: {
                if (task.getIntervalMinutes() <= 0) return null;
                LocalDateTime next = task.getLastRunAt() == null
                        ? now : task.getLastRunAt().plusMinutes(task.getIntervalMinutes());
                return millisUntil(next, now);
            }

            case INTERVAL_SECONDS: {
                if (task.getIntervalSeconds() <= 0) return null;
                LocalDateTime next = task.getLastRunAt() == null
                        ? now : task.getLastRunAt().plusSeconds(task.getIntervalSeconds());
                return millisUntil(next, now);
            }

            default:
                return null;
        }
    }

    /**
     * True when {@code occurrence} (today's configured time) has only just passed, within the delivery
     * jitter tolerance, and the task has not already run for it. An event is delivered at its target
     * instant or a few ms after, so "the next occurrence after now" is already tomorrow by then; without
     * this a DAILY/WEEKLY task re-validated at delivery was judged "not due" and re-armed for ~24 h and
     * so never ran.
     */
    static boolean justDue(LocalDateTime occurrence, LocalDateTime now, LocalDateTime lastRunAt) {
        if (occurrence.isAfter(now)) return false;
        if (Duration.between(occurrence, now).toMillis() > DUE_TOLERANCE_MS) return false;
        return lastRunAt == null || lastRunAt.isBefore(occurrence);
    }

    static long millisUntil(LocalDateTime target, LocalDateTime now) {
        return Math.max(0L, Duration.between(now, target).toMillis());
    }

    /** Parses "MON", "MONDAY", etc. (case-insensitive, prefix-tolerant) to a DayOfWeek, or null if unrecognized. */
    static DayOfWeek parseDayOfWeek(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String norm = raw.trim().toUpperCase();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().equals(norm) || d.name().startsWith(norm) || norm.startsWith(d.name().substring(0, 3))) {
                return d;
            }
        }
        return null;
    }
}
