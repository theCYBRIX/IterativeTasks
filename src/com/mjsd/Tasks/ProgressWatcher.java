package com.mjsd.Tasks;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.mjsd.Tasks.TaskExecuter.TaskExecutionListener;

public class ProgressWatcher<E extends IterativeTask> {
    final private TaskExecuter<? extends E> TO_WATCH;
    final private ProgressListener<? super E> CALLBACKS;
    final private Runnable UPDATE_CALL;
    final private FeedbackProvider FEEDBACK_PROVIDER = new FeedbackProvider();
    private int updateDelayMS;
    private boolean autoWatching = false;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledUpdates = null;

    public ProgressWatcher(TaskExecuter<? extends E> toWatch, ProgressListener<? super E> callbacks, int updateDelayMS) throws NullPointerException, IllegalArgumentException{
        this.TO_WATCH = Objects.requireNonNull(toWatch);
        this.CALLBACKS = Objects.requireNonNull(callbacks);

        if(updateDelayMS <= 0) throw new IllegalArgumentException("Delay between updates may not be less than or equal to zero.");
        this.updateDelayMS = updateDelayMS;

        UPDATE_CALL = () -> {
            E task = TO_WATCH.getTask();
            CALLBACKS.onStateUpdated(TO_WATCH, task.getStepsCompleted(), task.getTotalNumSteps());
        };

        this.setAutoWatchingEnabled(true);
    }


    public void start() throws CurrentlyRunningException{
        if(this.isRunning()) throw new CurrentlyRunningException();

        scheduler = Executors.newScheduledThreadPool(1);
        scheduledUpdates = scheduler.scheduleWithFixedDelay(UPDATE_CALL, 0, updateDelayMS, TimeUnit.MILLISECONDS);
    }

    /**
     * @return {@code false} if the task could not be stopped, typically because it has not been started, or has already completed; {@code true} otherwise.
     */
    public boolean stop(){
        if(scheduler == null) return false;

        scheduledUpdates.cancel(false);
        scheduler.shutdown();

        scheduler = null;
        scheduledUpdates = null;

        return true;
    }

    /**
     * When {@code true} (default) the ProgressWatcher starts and stops itself automatically via callbacks from the FileDisplacement. When {@code false} the user is expected to handle starting and stopping via the {@code start()} and {@code stop()} methods. 
     * @param enabled Weather the ProgressWatcher should start and stop automatically.
     * @implNote If the ProgressWatcher is running when this method is called, it will remain running.
     * @see #start()
     * @see #stop()
     */
    public void setAutoWatchingEnabled(boolean enabled){
        if(enabled == autoWatching) return;

        autoWatching = enabled;

        if(autoWatching){
            TO_WATCH.attachCallback(FEEDBACK_PROVIDER);
            if(TO_WATCH.isRunning() && !this.isRunning()) this.start();
        } else
            TO_WATCH.detachCallback(FEEDBACK_PROVIDER);
    }



    public boolean isRunning(){
        return (scheduler != null && !scheduler.isShutdown());
    }

    private class FeedbackProvider implements TaskExecutionListener<E>{

        @Override
        public void onTaskStarted(TaskExecuter<? extends E> executer, E process) {
            try {
                start();
            } catch (Exception e) {}
        }

        @Override public void onTaskPaused(TaskExecuter<? extends E> executer, E process) {}
        @Override public void onTaskResumed(TaskExecuter<? extends E> executer, E process) {}
        @Override public void onTaskFailed(TaskExecuter<? extends E> executer, E process, Exception reason) {}
        @Override public void onTaskCancelled(TaskExecuter<? extends E> executer, E process) {}
        @Override public void onTaskCompleted(TaskExecuter<? extends E> executer, E process) {}

        @Override
        public void onTaskEnd(TaskExecuter<? extends E> executer, E process) {
            stop();
            UPDATE_CALL.run();
        }

    } 

    @FunctionalInterface
    public static interface ProgressListener<T extends IterativeTask> {
        public void onStateUpdated(TaskExecuter<? extends T> process, long stepsCompleted, long totalNumSteps);
    }

}
