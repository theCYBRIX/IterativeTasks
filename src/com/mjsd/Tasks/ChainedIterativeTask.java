package com.mjsd.Tasks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Allows for the completion of multiple IterativeTask objects in sequence, as a single IterativeTask.
 */
public class ChainedIterativeTask<E extends IterativeTask> implements IterativeTask, Collection<E>{
    final private LinkedList<CachedTask<? extends E>> TASK_QUEUE = new LinkedList<>();
    final private LinkedList<E> COMPLETED_TASKS = new LinkedList<>(),
                                FAILED_TASKS = new LinkedList<>();
    private E currentTask;
    private FailedTaskHandler<? extends E> failedTaskHandler = null;
    
    private long totalNumSteps = 0, stepsCompleted = 0;
    private boolean haltOnException = false,
                    undoOnException = false,
                    hasNext = false,
                    running = false;

    private LinkedList<Exception> unhandledExceptions = new LinkedList<>();

    public ChainedIterativeTask(){}

    public ChainedIterativeTask(Collection<? extends E> tasks) throws NullPointerException{
        addAll(tasks);
    }


    /**
     * @param failedTaskHandler The FailedTaskHandler for this object. If set to {@code null}, no FailedTaskHandler will be used.
     */
    public void setFailedTaskHandler(FailedTaskHandler<? extends E> failedTaskHandler) {
        this.failedTaskHandler = failedTaskHandler;
    }

    /**
     * Weather the operation should be canceled if a task throws an exception.
     * @param enabled if {@code true} exceptions cause the operation to halt; if {@code false} (default) unhandled failed tasks cause the operation to fail.
     * @see #setUndoOnException()
     * @see #setFailedTaskHandler()
     */
    public void setHaltOnException(boolean enabled) {
        this.haltOnException = enabled;
    }

    /**
     * Weather all completed tasks are undone when an operation fails. Only takes effect if {@code setHaltOnException()} has been set to {@code true}. Tasks only fail if they throw an exception, and the FailedTaskHandler either is not provided, or returns no alternative operation.
     * @param enabled when {@code true}, calls {@code undo()} on all completed tasks if an exception is encountered; Otherwise if {@code false} (default), completed tasks are left unchanged.
     * @see #setHaltOnException()
     * @see #setFailedTaskHandler()
     */
    public void setUndoOnException(boolean enabled) {
        this.undoOnException = enabled;
    }

    /**
     * @return This object's FailedTaskHandler, if any was set.
     */
    public Optional<FailedTaskHandler<? extends E>> getFailedTaskHandler() {
        return Optional.ofNullable(failedTaskHandler);
    }

    /**
     * @return an unmodifiable view of the failed task list. (Ongoing changes will be reflected in the returned list.)
     */
    public List<E> getFailedTasks() {
        return new ArrayList<>(FAILED_TASKS);
    }

    public List<Exception> getUnhandledExceptions() {
        return new ArrayList<>(unhandledExceptions);
    }


    /**
     * @return an unmodifiable view of the completed task list. (Ongoing changes will be reflected in the returned list.)
     */
    public List<E> getCompletedTasks() {
        return new ArrayList<>(COMPLETED_TASKS);
    }

    private void refreshSourceSize(){
        synchronized(TASK_QUEUE){
            long steps = 0;
            for (CachedTask<? extends E> t : TASK_QUEUE) {
                steps += t.refresh().getNumSteps();
            }
            totalNumSteps = steps;
        }
    }

	public synchronized void undo() throws Exception {
        if(running) throw new CurrentlyRunningException();
        
		for(int i = 0; i < COMPLETED_TASKS.size(); i++){
			try {
				COMPLETED_TASKS.get(i).undo();
                COMPLETED_TASKS.remove(i);
			} catch (Exception e) {
                unhandledExceptions.add(e);
            }
		}
	}

    @Override
    public synchronized void prepare() throws Exception {
        if(running) throw new CurrentlyRunningException();
        stepsCompleted = 0;

        if(FAILED_TASKS.size() > 0 || COMPLETED_TASKS.size() > 0){
            FAILED_TASKS.clear();
            COMPLETED_TASKS.clear();
            refreshSourceSize();
        }

        running = true;
        advanceQueue();
    }

    @Override
    public boolean hasNext() {
        return hasNext;
    }

    @Override
    public void next() throws Exception {

        if(currentTask.hasNext()){
            try {
                currentTask.next();
            } catch (Exception e) {
                currentTask.failed();
                handleException(e);
            }
        } else {
            currentTask.completed();
            currentTask.finish();

            COMPLETED_TASKS.add(currentTask);

            synchronized(TASK_QUEUE){
                long taskLength = currentTask.getTotalNumSteps();
                stepsCompleted += taskLength;
                totalNumSteps += taskLength;
            }
            
            advanceQueue();
        }

    }

    private void advanceQueue() throws Exception{
        try {
            synchronized(TASK_QUEUE){
                CachedTask<? extends E> next = TASK_QUEUE.removeFirst();
                currentTask = next.getTask();
                totalNumSteps -= next.getNumSteps().longValue();
            }
        } catch (NoSuchElementException e) {
            currentTask = null;
            hasNext = false;
            return;
        }

        try {
            currentTask.prepare();
            hasNext = true;
        } catch (Exception e) {
            handleException(e);
        }
    }

    private void handleException(Exception e) throws Exception{
        currentTask.undo();

        if(failedTaskHandler != null){
            E replacement = failedTaskHandler.handle(currentTask, e);
            if(replacement != null){
                addFirst(replacement);
                advanceQueue();
                return;
            }
        }

        FAILED_TASKS.add(currentTask);

        if(haltOnException) {
            throw e;
        }

        synchronized(TASK_QUEUE){
            totalNumSteps -= currentTask.getTotalNumSteps();
        }
    }

    @Override
    public void finish() {
        running = false;
    }

    @Override
    public void failed() {
        if(currentTask != null)
            currentTask.failed();

        try {
            if(undoOnException){
                this.undo();
            }
        } catch (Exception e) {
            unhandledExceptions.add(e);
        }
    }

    @Override
    public void cancelled() {
        if(currentTask != null) currentTask.cancelled();
    }

    @Override
    public void completed() {}

    @Override
    public long getStepsCompleted() {
        if(currentTask == null)
            return stepsCompleted;
        else
            return stepsCompleted + currentTask.getStepsCompleted();
    }

    @Override
    public long getTotalNumSteps() {
        if(currentTask == null)
            return totalNumSteps;
        else
            return totalNumSteps + currentTask.getTotalNumSteps();
    }


    /**
     * Moves all failed tasks to the task queue.
     * @return {@code true} if any tasks were added to the queue; {@code false} otherwise.
     * @throws CurrentlyRunningException If the process is running when this method is called.
     */
    public boolean retry() {
        if(FAILED_TASKS.size() == 0) return false;

        synchronized(TASK_QUEUE){
            this.addAll(FAILED_TASKS);
            FAILED_TASKS.clear();
        }

        return true;
    }



    @Override
    public int size() {
        synchronized(TASK_QUEUE){
            return TASK_QUEUE.size();
        }
    }

    @Override
    public boolean isEmpty() {
        synchronized(TASK_QUEUE){
            return TASK_QUEUE.isEmpty();
        }
    }

    @Override
    public boolean contains(Object o) {
        synchronized(TASK_QUEUE){
            return TASK_QUEUE.contains(o);
        }
    }

    @Override
    public Iterator<E> iterator() {
        synchronized(TASK_QUEUE){
            return new Iterator<E>() {
                Iterator<CachedTask<? extends E>> iterator = TASK_QUEUE.iterator();

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public E next() throws NoSuchElementException {
                    return iterator.next().getTask();
                }
            };
        }
    }

    @Override
    public Object[] toArray() {
        synchronized(TASK_QUEUE){
            return TASK_QUEUE.toArray();
        }
    }

    @Override
    public <T> T[] toArray(T[] a) throws NullPointerException {
        synchronized(TASK_QUEUE){
            return TASK_QUEUE.toArray(a);
        }
    }

    @Override
    public boolean add(E e) throws NullPointerException {
        if(running && !hasNext) return false;

        CachedTask<E> newEntry = new CachedTask<>(e);

        synchronized(TASK_QUEUE){
            if(TASK_QUEUE.add(newEntry)){
                totalNumSteps += newEntry.getNumSteps().longValue();
                return true;
            }
        }

        return false;
    }

    public boolean addFirst(E e) throws NullPointerException {
        if(running && !hasNext) return false;

        CachedTask<E> newEntry = new CachedTask<E>(e);

        synchronized(TASK_QUEUE){
            TASK_QUEUE.addFirst(newEntry);
            totalNumSteps += newEntry.getNumSteps().longValue();
        }

        return true;
    }


    @Override
    public boolean remove(Object o){
        boolean removed = false;

        if(o instanceof IterativeTask)
            synchronized(TASK_QUEUE){
                int index = TASK_QUEUE.indexOf(o);

                if(index >= 0){
                    CachedTask<? extends E> task = TASK_QUEUE.remove(index);
                    totalNumSteps -= task.getNumSteps();
                    removed = true;
                }
            }

        return removed;
    }

    @Override
    public boolean containsAll(Collection<?> c) throws NullPointerException{
        synchronized(TASK_QUEUE){
            return TASK_QUEUE.containsAll(Objects.requireNonNull(c));
        }
    }

    @Override
    public boolean addAll(Collection<? extends E> c) throws NullPointerException{
        boolean addedAny = false;

        for (E task : Objects.requireNonNull(c)) {
            if(task == null) continue;

            this.add(task);
            if(addedAny) continue;
            
            addedAny = true;
        }

        return addedAny;
    }

    @Override
    public boolean removeAll(Collection<?> c) throws NullPointerException{
        boolean anyRemoved = false;

        for (Object object : Objects.requireNonNull(c)) {
            if(this.remove(object)) anyRemoved = true;
        }

        return anyRemoved;
    }

    @Override
    public boolean retainAll(Collection<?> c) throws NullPointerException{
        boolean listChanged;

        synchronized(TASK_QUEUE){
            listChanged = TASK_QUEUE.retainAll(Objects.requireNonNull(c));
        }
        
        if(listChanged){
            refreshSourceSize();
        }

        return listChanged;
    }

    @Override
    public void clear(){
        if(running && !hasNext) return;
        synchronized(TASK_QUEUE){
            TASK_QUEUE.clear();
            totalNumSteps = 0;
        }
    }

    private static class CachedTask<E extends IterativeTask>{
        final E task;
        Long steps;

        public CachedTask(E task) throws NullPointerException{
            this.task = Objects.requireNonNull(task);
            this.refresh();
        }

        public E getTask() {
            return task;
        }

        public Long getNumSteps() {
            return steps;
        }

        public CachedTask<E> refresh(){
            steps = Long.valueOf(task.getTotalNumSteps());
            return this;
        }

        @Override
        public boolean equals(Object obj) {
            if(obj == null) return false;

            if(obj instanceof TaskExecuter){
                return task.equals(obj);
            }

            if(obj instanceof CachedTask){
                CachedTask<?> other = (CachedTask<?>)obj;
                return task.equals(other.getTask());
            }

            return false;
        }
    }
    
}
