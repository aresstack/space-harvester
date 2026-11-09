package com.aresstack.spaceharvester.app.event;

/** Ein App-Ereignis mit Nutzlast; übernommen aus MainframeMate ({@code de.bund.zrb.event.ApplicationEvent}). */
public interface ApplicationEvent<T> {
    T getPayload();
}
