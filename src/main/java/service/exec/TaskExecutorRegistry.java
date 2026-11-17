package service.exec;

import model.ScheduledTask;

import java.util.EnumMap;
import java.util.Map;

/** Finds the {@link TaskExecutor} for a task type. */
public final class TaskExecutorRegistry {

    private final Map<ScheduledTask.TaskType, TaskExecutor> executors = new EnumMap<>(ScheduledTask.TaskType.class);

    /**
     * Registers an executor for its type.
     *
     * @throws IllegalStateException if the type already has one, so two executors never silently compete
     */
    public synchronized TaskExecutorRegistry register(TaskExecutor executor) {
        TaskExecutor previous = executors.putIfAbsent(executor.type(), executor);
        if (previous != null) {
            throw new IllegalStateException("A task executor is already registered for " + executor.type());
        }
        return this;
    }

    /** The executor for {@code type}, or null when none is registered (or {@code type} is null). */
    public synchronized TaskExecutor find(ScheduledTask.TaskType type) {
        return type == null ? null : executors.get(type);
    }
}
