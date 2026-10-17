package com.aresstack.spaceharvester.application.jobs;

/** Es warten oder laufen bereits so viele Aufträge, wie {@link JobLimits} zulässt. */
public class JobCapacityExceededException extends RuntimeException {

    public JobCapacityExceededException(int limit) {
        super("Zu viele offene Aufträge (höchstens " + limit + "), bitte später erneut versuchen");
    }

    /** Grenze je Auslöser überschritten; die Meldung nennt keine fremden Aufträge. */
    public static JobCapacityExceededException perOwner(int limit) {
        return new JobCapacityExceededException("Zu viele offene Aufträge dieses Clients (höchstens " + limit
                + "), bitte auf deren Ende warten");
    }

    private JobCapacityExceededException(String message) {
        super(message);
    }
}
