package com.aresstack.spaceharvester.application.sync;

/** Ein anderer Lauf hat inzwischen eine Generation desselben Space bestätigt; dieser Lauf muss neu beginnen. */
public class SyncConflictException extends RuntimeException {

    public SyncConflictException(String message) {
        super(message);
    }
}
