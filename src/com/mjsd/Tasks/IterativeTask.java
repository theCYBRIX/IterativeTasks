package com.mjsd.Tasks;

/**
 * The {@code IterativeTask} interface is intended to be used in conjunction with the {@code TaskExecuter} class, to complete piecewise
 * operations in an asynchronous manner.
 * <p>
 * {@code IterativeTasks}, when run within a {@code TaskExecuter} have the benefit of being cancelled and undone, or paused and resumed dynamically,
 * while also providing feedback on the progression of the given task. 
 * @see TaskExecuter
 */
public interface IterativeTask {
    
    /**
     * Prepares the task for execution.
     * After this method is called, {@code hasNext()} should result in {@code true}, until no more calls to {@code next()} are required for task completion.
     * @throws Exception if the task cannot be prepared for execution.
     * @see #hasNext()
     * @see #next()
     */
    public void prepare() throws Exception;

    /**
     * Reports if the {@link IterativeTask} has more steps to complete within the {@code next()} method.
     * @return {@code true} if more calls to {@code next()} are required for task completion.
     * @apiNote This should result {@code false} prior to {@code prepare()} being called; after which it should result {@code true}, until no more calls to {@code next()} are required, and it is safe to call {@code finish()}.
     * @see #prepare()
     * @see #next()
     * @see #finish()
     */
    public boolean hasNext();

    /**
     * Performs the a piece of the {@link IterativeTask}.
     * This method may result in one or more steps being completed, but should not block for an extended period of time, as pausing and canceling are handled between calls.
     * @throws Exception indicates that the process has been compromised. The task will register as failed, and attempt to undo any changes.
     * @see #hasNext()
     * @see #undo()
     * @see Callbacks#onTaskFailed()
     */
    public void next() throws Exception;

    /**
     * Called when the task stops.
     * @apiNote This method will be called when either all necessary calls to {@code next()} are completed, or an exception is thrown during execution.
     * @see #prepare()
     * @see #hasNext()
     * @see #next()
     */
    public void finish();

    /**
     * Called when the task fails.
     * @apiNote This method will be called when an exception thrown during execution is not handled by a {@link FailedTaskHandler}.
     */
    public void failed();

    /**
     * Called if the task is cancelled. Allows for changes to be undone, if necessary.
     */
    public void cancelled();

    /**
     * Called once all necessary calls to {@code next()} have been executed without any exceptions being thrown.
     */
    public void completed();
    
    /**
     * Undoes all changes made during the execution of this task. 
     * @throws Exception if any changes made could not be undone.
     * @apiNote This method may be called at any time, even if the task has never started, failed, completed or been canceled.
     */
    public void undo() throws Exception;

    /**
     * @return the number of steps that have been completed.
     * @apiNote It is up to the implementer to ensure that the steps are counted, and result equal to {@code getTotalNumSteps()} upon task completion.
     * @see #getTotalNumSteps()
     * @see ProgressWatcher
     */
    public long getStepsCompleted();

    /**
     * @return the number of steps this task needs in order to complete.
     * @apiNote This value does not need to be constant, but can result in inaccurate progress feedback if varying.
     * @see #getStepsCompleted()
     * @see ProgressWatcher
     */
    public long getTotalNumSteps();
    
}
