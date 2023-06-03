package com.mjsd.Tasks;

/**
 * Thrown when an operation can't be performed, because it involves a dependency for a currently running process.
 */
public class CurrentlyRunningException extends RuntimeException{
    public CurrentlyRunningException(){ super(); }
    public CurrentlyRunningException(String msg){ super(msg); }
}
