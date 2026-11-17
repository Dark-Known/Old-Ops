package ui;

import model.ScheduledTask;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A custom ListModel for managing a list of ScheduledTask objects.
 * Replaces the parallel taskIds/tableModel structure from the JTable version.
 */
public class TaskListModel extends AbstractListModel<ScheduledTask> {

    private final List<ScheduledTask> tasks = new ArrayList<>();

    public TaskListModel() {
    }

    @Override
    public int getSize() {
        return tasks.size();
    }

    @Override
    public ScheduledTask getElementAt(int index) {
        if (index < 0 || index >= tasks.size()) return null;
        return tasks.get(index);
    }

    public void setTasks(List<ScheduledTask> newTasks) {
        int oldSize = tasks.size();
        tasks.clear();
        tasks.addAll(newTasks);
        
        if (oldSize > 0) {
            fireIntervalRemoved(this, 0, oldSize - 1);
        }
        if (tasks.size() > 0) {
            fireIntervalAdded(this, 0, tasks.size() - 1);
        }
    }

    public void addTask(ScheduledTask task) {
        int index = tasks.size();
        tasks.add(task);
        fireIntervalAdded(this, index, index);
    }

    public void removeTask(int index) {
        tasks.remove(index);
        fireIntervalRemoved(this, index, index);
    }

    public void updateTask(int index, ScheduledTask task) {
        tasks.set(index, task);
        fireContentsChanged(this, index, index);
    }

    public int findTaskIndex(String taskId) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(taskId)) {
                return i;
            }
        }
        return -1;
    }

    public ScheduledTask findTask(String taskId) {
        int idx = findTaskIndex(taskId);
        return idx >= 0 ? tasks.get(idx) : null;
    }

    public List<ScheduledTask> getTasks() {
        return new ArrayList<>(tasks);
    }
}
