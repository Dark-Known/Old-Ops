package service.exec;

import model.ScheduledTask;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything an executor is handed for one run. Grouped in a record so a new
 * input can be added later without changing the signature of every executor.
 *
 * @param task             the task being run
 * @param log              receives the run's log lines
 * @param changedFileNames files the watch layer saw change for this run, or empty for an ordinary
 *                         scheduled/manual run (see {@code TransferService#executeTransfer})
 */
public record RunContext(ScheduledTask task, Consumer<String> log, Set<String> changedFileNames) {
}
