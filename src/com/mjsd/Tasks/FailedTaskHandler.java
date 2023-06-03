package com.mjsd.Tasks;

/**
 * Used to handle exceptions caused by FileDisplacement tasks within a GroupDisplacement.
 * May be used to retry, or take note of failed tasks.
 */
public interface FailedTaskHandler<E extends IterativeTask> {
    /**
     * Called when a task throws an exception, allowing the implementer to handle the exception.
     * If the {@code null} is returned, the task will be discarded and added to the fail; otherwise, the returned object will take the failed task's place (intended to retry the operation), and the failed task will not be added to the list of failed tasks.
     * @param task The task that has thrown an Exception.
     * @param reason The exception thrown by the given task.
     * @return a task to run in the place of the failed task.
     * @implNote If an object is returned when the failed task is part of a ChainedIterativeTask, the ChainedIterativeTask will lose all references to the failed task.
     */
    public E handle(IterativeTask task, Exception reason);
}