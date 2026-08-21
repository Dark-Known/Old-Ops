package model;

import java.time.LocalDateTime;

/**
 * One row of task run history, as stored in the SQLite run-history database
 * (see {@link service.RunHistoryService}). This is deliberately a summary
 * record, not the full text log — the Logs panel is meant to answer
 * "what happened on this run" at a glance:
 * <ul>
 *   <li>{@code status == SUCCESS}   → {@link #reason} holds a short success summary</li>
 *   <li>{@code status == FAILED}    → {@link #reason} holds why it failed</li>
 *   <li>{@code status == SKIPPED}   → {@link #reason} holds why it was skipped</li>
 * </ul>
 * {@link #details} holds the full captured log text for that single run, for
 * when the short reason isn't enough and someone needs to drill in.
 */
public class TaskRunRecord {

    public enum Status { SUCCESS, FAILED, SKIPPED }

    private long id;
    private String taskId;
    private String taskName;
    private String taskType;
    private Status status;
    private String reason;
    private String details;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private long durationMs;

    public TaskRunRecord() {}

    public long getId()                          { return id; }
    public void setId(long id)                   { this.id = id; }

    public String getTaskId()                    { return taskId; }
    public void setTaskId(String taskId)         { this.taskId = taskId; }

    public String getTaskName()                  { return taskName; }
    public void setTaskName(String taskName)     { this.taskName = taskName; }

    public String getTaskType()                  { return taskType; }
    public void setTaskType(String taskType)     { this.taskType = taskType; }

    public Status getStatus()                    { return status; }
    public void setStatus(Status status)         { this.status = status; }

    public String getReason()                    { return reason; }
    public void setReason(String reason)         { this.reason = reason; }

    public String getDetails()                   { return details; }
    public void setDetails(String details)       { this.details = details; }

    public LocalDateTime getStartedAt()           { return startedAt; }
    public void setStartedAt(LocalDateTime t)     { this.startedAt = t; }

    public LocalDateTime getEndedAt()             { return endedAt; }
    public void setEndedAt(LocalDateTime t)       { this.endedAt = t; }

    public long getDurationMs()                   { return durationMs; }
    public void setDurationMs(long durationMs)    { this.durationMs = durationMs; }
}
