package com.mjsd.Tasks;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TaskExecuter<E extends IterativeTask> implements Runnable {

    final private LinkedList<TaskExecutionListener<? super E>> CALLBACKS = new LinkedList<>();
    private E task;

    private ExecutorService callbackService;
    private Thread processThread = null;

    private Object pauseObject = new Object();
    private boolean running = false,
                    completed = false,
                    paused = false,
                    canceled = false,
                    retry;

    private Exception reasonOfFailure = null;
    private FailedTaskHandler<? extends E> failedTaskHandler = null;


    public TaskExecuter(E task) throws NullPointerException{
        this.task = Objects.requireNonNull(task);
    }

    public TaskExecuter(E task, TaskExecutionListener<? super E> callbacks) throws NullPointerException{
        this.task = Objects.requireNonNull(task);
        attachCallback(callbacks);
    }

    public TaskExecuter(E task, Collection<? extends TaskExecutionListener<? super E>> callbacks) throws NullPointerException{
        this.task = Objects.requireNonNull(task);
        attachCallbacks(callbacks);
    }



    /**
     * Starts this task in a new Thread.
     * @return the Thread which is now running this task.
     * @throws CurrentlyRunningException if this task is already running.
     */
    final public synchronized Thread start() throws CurrentlyRunningException{
        if(running) throw new CurrentlyRunningException();
        Thread newThread = new Thread(this);
        newThread.start();
        return newThread;
    }

    /**
     * @throws CurrentlyRunningException if the current task is already being run in another thread.
     */
    final public void run() throws CurrentlyRunningException {
        if(running) throw new CurrentlyRunningException();
        running = true;
        processThread = Thread.currentThread();

        try{
            try {
                reasonOfFailure = null;

                running = true;
                processThread = Thread.currentThread();
                callbackService = Executors.newFixedThreadPool(1);
                
                canceled = false;
                completed = false;
                onTaskStarted();

                do{
                    retry = false;

                    try {
                        task.prepare();
                        iterateTaskSteps();
                    } catch (Exception e) {
                        handleException(e);
                    }
                } while(retry);

                if(canceled){
                    onTaskCancelled();
                    task.cancelled();
                } else {
                    completed = true;
                    onTaskCompleted();
                    task.completed();
                }
                
            } catch (Exception e) {
                throw e;
            }

        } catch (Exception e){
            reasonOfFailure = e;
            onTaskFailed(e);
            task.failed();

        } finally {
            task.finish();
            onTaskEnd();

            processThread = null;
            running = false;
            callbackService.shutdown();
        }
    }

    final private void iterateTaskSteps() throws Exception{
        while(task.hasNext()){
            if(Thread.interrupted()){
                if(canceled) break;
                try {
                    onTaskPaused();
                    
                    synchronized(pauseObject){
                        paused = true;
                        pauseObject.wait();
                    }

                } catch (InterruptedException e) {
                    if(canceled) break;

                } finally{
                    paused = false;
                }

                onTaskResumed();
            }

            task.next();
        }
    }

    private void handleException(Exception e) throws Exception{
        if(failedTaskHandler == null) throw e;

        E replacement = failedTaskHandler.handle(task, e);
        if(replacement == null) throw e;

        task = replacement;
        retry = true;
    }


    /**
     * @param failedTaskHandler The FailedTaskHandler for this object. If set to {@code null}, no FailedTaskHandler will be used.
     */
    public void setFailedTaskHandler(FailedTaskHandler<? extends E> failedTaskHandler) {
        this.failedTaskHandler = failedTaskHandler;
    }

    /**
     * @return This object's FailedTaskHandler, if any was set.
     */
    public Optional<FailedTaskHandler<? extends E>> getFailedTaskHandler() {
        return Optional.ofNullable(failedTaskHandler);
    }

    /**
     * Cancels this task.
     */
    final public void cancel(){
        if(!running) return;
        canceled = true;
        processThread.interrupt();
    }


    /**
     * Pauses this task.
     */
    final public void pause(){
        if(!running) return;
        if(paused || processThread.isInterrupted()) return;
        processThread.interrupt();
    }

    /**
     * Undoes the changes made by the IterativeTask.
     * @throws CurrentlyRunningException if this method is called while the IterativeTask is running.
     * @throws Exception if an exception causes the undo process to fail.
     */
    final public void undo() throws CurrentlyRunningException, Exception{
        if(running) throw new CurrentlyRunningException();
        
        task.undo();
    }

    /**
     * Resumes this task.
     */
    final public void resume(){
        if(!paused) return;
        
        synchronized(pauseObject){
            pauseObject.notifyAll();
        }
    }


    /**
     * @return {@code true} if the source file has successfully been copied to the destination. Otherwise {@code false}.
     */
    final public boolean isCompleted() {
        return completed;
    }


    /**
     * @return {@code true} if the IterativeTask process is running (also true when paused). Otherwise {@code false}.
     */
    final public boolean isRunning() {
        return running;
    }

    /**
     * @return {@code true} if the IterativeTask process is paused. Otherwise {@code false}.
     */
    final public boolean isPaused() {
        return paused;
    }

    /**
     * @return This executer's task.
     */
    public E getTask() {
        return task;
    }

    /**
     * @return the Thread running this executer, if one exists.
     */
    final public Optional<Thread> getProcessThread() {
        return Optional.ofNullable(processThread);
    }

    /**
     * @return the exception resulting in task failure, if the task failed.
     */
    public Optional<Exception> getReasonOfFailure() {
        return Optional.ofNullable(reasonOfFailure);
    }

    /**
     * Attaches the callback to this task. The methods of this callback will be called when the designated milestones are reached.
     * @param callback the callback object to attach to this task.
     * @throws NullPointerException if the parameter is null.
     */
    final public void attachCallback(TaskExecutionListener<? super E> callback) throws NullPointerException{
        this.CALLBACKS.add(Objects.requireNonNull(callback));
    }

    /**
     * Attaches all callback objects within the given Collection to this task. The methods of these callback objects will be called when the designated milestones are reached.
     * @param callbacks the Collection of callback objects to attach to this task.
     * @throws NullPointerException if the Collection contains a null value. 
     * @implNote if a {@code NullPointerException} is thrown, no objects from the Collection will be attached.
     */
    final public void attachCallbacks(Collection<? extends TaskExecutionListener<? super E>> callbacks) throws NullPointerException{
        if(callbacks.contains(null)) throw new NullPointerException();
        this.CALLBACKS.addAll(callbacks);
    }

    /**
     * Detaches the callback from this task. The methods of this callback will no longer be called by this task.
     * @param callback the callback object to detach from this task.
     * @return {@code true} if the specified object was removed; {@code false} otherwise.
     */
    final public boolean detachCallback(TaskExecutionListener<? super E> callback){
        return this.CALLBACKS.remove(callback);
    }

    /**
     * Detaches all callback objects from within the given Collection from this task. The methods of these callback objects will no longer be called by this task.
     * @param callback the Collection of callback objects to detach from this task.
     * @return {@code true} if any of the specified objects were removed; {@code false} otherwise.
     * @throws NullPointerException if the Collection is null. 
     */
    final public boolean detachCallbacks(Collection<? extends TaskExecutionListener<? super E>> callbacks) throws NullPointerException{
        return this.CALLBACKS.removeAll(callbacks);
    }

    /**
     * Creates and returns a new ProgressWatcher for this IterativeTask object. 
     * @param callbacks The callback for the watcher to use. 
     * @param updateDelayMS The number of milliseconds between callbacks.
     * @return the created ProgressWatcher.
     * @throws IllegalArgumentException if {@code updateDelayMS <= 0}
     * @throws NullPointerException if a null pointer was passed to the method
     */
    final public ProgressWatcher<E> createProgressWatcher(ProgressWatcher.ProgressListener<? super E> callbacks, int updateDelayMS) throws IllegalArgumentException, NullPointerException{
        return new ProgressWatcher<E>(this, callbacks, updateDelayMS);
    }



    final private void onTaskStarted(){
        final TaskExecuter<E> EXECUTER = this;
        final E TASK = this.task;
        Runnable callback = new Runnable() {
            @Override
            public void run() {
                for(TaskExecutionListener<? super E> c : CALLBACKS)
                    c.onTaskStarted(EXECUTER, TASK);
            }
        };

        callbackService.submit(callback);
    }

    final private void onTaskPaused(){
        final TaskExecuter<E> EXECUTER = this;
        final E TASK = this.task;
        Runnable callback = new Runnable() {
            @Override
            public void run() {
                for(TaskExecutionListener<? super E> c : CALLBACKS)
                    c.onTaskPaused(EXECUTER, TASK);
            }
        };

        callbackService.submit(callback);
    }

    final private void onTaskResumed(){
        final TaskExecuter<E> EXECUTER = this;
        final E TASK = this.task;
        Runnable callback = new Runnable() {
            @Override
            public void run() {
                for(TaskExecutionListener<? super E> c : CALLBACKS)
                    c.onTaskResumed(EXECUTER, TASK);
            }
        };

        callbackService.submit(callback);
    }


    final private void onTaskFailed(Exception reason){
        final TaskExecuter<E> EXECUTER = this;
        final E TASK = this.task;
        Runnable callback = new Runnable() {
            @Override
            public void run() {
                for(TaskExecutionListener<? super E> c : CALLBACKS)
                    c.onTaskFailed(EXECUTER, TASK, reason);
            }
        };

        callbackService.submit(callback);
    }

    final private void onTaskCancelled(){
        final TaskExecuter<E> EXECUTER = this;
        final E TASK = this.task;
        Runnable callback = new Runnable() {
            @Override
            public void run() {
                for(TaskExecutionListener<? super E> c : CALLBACKS)
                    c.onTaskCancelled(EXECUTER, TASK);
            }
        };

        callbackService.submit(callback);
    }

    final private void onTaskCompleted(){
        final TaskExecuter<E> EXECUTER = this;
        final E TASK = this.task;
        Runnable callback = new Runnable() {
            @Override
            public void run() {
                for(TaskExecutionListener<? super E> c : CALLBACKS)
                    c.onTaskCompleted(EXECUTER, TASK);
            }
        };

        callbackService.submit(callback);
    }

    final private void onTaskEnd(){
        final TaskExecuter<E> EXECUTER = this;
        final E TASK = this.task;
        Runnable callback = new Runnable() {
            @Override
            public void run() {
                for(TaskExecutionListener<? super E> c : CALLBACKS)
                    c.onTaskEnd(EXECUTER, TASK);
            }
        };

        callbackService.submit(callback);
    }



    public static interface TaskExecutionListener<E extends IterativeTask>{

        /**
         * Called when the IterativeTask has been started.
         * @param executer The executer performing the task.
         * @param task The task that has been started.
         * @see #onTaskEnd()
         */
        public void onTaskStarted(TaskExecuter<? extends E> executer, E task);

        /**
         * Called when the IterativeTask enters a paused state.
         * @param executer The executer performing the task.
         * @param task The task that has been paused.
         * @see #onTaskResumed()
         */
        public void onTaskPaused(TaskExecuter<? extends E> executer, E task);

        /**
         * Called when the IterativeTask exits the paused state.
         * @param executer The executer performing the task.
         * @param task The task that has been resumed.
         * @see #onTaskPaused()
         */
        public void onTaskResumed(TaskExecuter<? extends E> executer, E task);

        /**
         * Called if and only if the IterativeTask has failed.
         * @param executer The executer performing the task.
         * @param task The task that has failed.
         * @param reason The Exception that has caused the task to fail.
         * @see #onTaskCompleted()
         * @see #onTaskCancelled()
         * @see #onTaskEnd()
         */
        public void onTaskFailed(TaskExecuter<? extends E> executer, E task, Exception reason);

        /**
         * Called if and only if the IterativeTask was cancelled.
         * @param executer The executer performing the task.
         * @param task The task that was cancelled.
         * @see #onTaskCompleted()
         * @see #onTaskFailed()
         * @see #onTaskEnd()
         */
        public void onTaskCancelled(TaskExecuter<? extends E> executer, E task);

        /**
         * Called if and only if the IterativeTask has finished successfully.
         * @param executer The executer performing the task.
         * @param task The task that has finished.
         * @see #onTaskCancelled()
         * @see #onTaskFailed()
         * @see #onTaskEnd()
         */
        public void onTaskCompleted(TaskExecuter<? extends E> executer, E task);

        /**
         * Called when the task has ends it's operation.
         * One of either {@code onTaskFinished()}, {@code onTaskCancelled()} or {@code onTaskFailed()} will be called prior to this.
         * @param executer The executer that has terminated.
         * @param task The task that was being performed.
         * @see #onTaskCompleted()
         * @see #onTaskCancelled()
         * @see #onTaskFailed()
         */
        public void onTaskEnd(TaskExecuter<? extends E> executer, E task);
    }
}
