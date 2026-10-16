package com.aresstack.spaceharvester.application.jobs;

/** Es warten oder laufen bereits so viele Aufträge, wie {@link JobLimits} zulässt. */
public class JobCapacityExceededException extends RuntimeException {

    public JobCapacityExceededException(int limit) {
        super("Zu viele offene Aufträge (höchstens " + limit + "), bitte später erneut versuchen");
    }
}
