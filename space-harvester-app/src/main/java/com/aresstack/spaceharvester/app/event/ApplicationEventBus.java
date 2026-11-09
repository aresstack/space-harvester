package com.aresstack.spaceharvester.app.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Einfacher app-weiter Ereignisbus mit typisierten und untypisierten Abonnements; übernommen aus MainframeMate
 * ({@code de.bund.zrb.event.ApplicationEventBus}). Abweichung: neben der gemeinsamen Instanz lässt sich je
 * {@code AppContext} ein eigener Bus anlegen, damit Fenster (und Tests) einander nicht hören.
 */
public final class ApplicationEventBus {

    private static final ApplicationEventBus INSTANCE = new ApplicationEventBus();

    public static ApplicationEventBus getInstance() {
        return INSTANCE;
    }

    private final List<Consumer<ApplicationEvent<?>>> anyListeners = new CopyOnWriteArrayList<>();
    private final Map<Class<?>, List<Consumer<?>>> typed = new ConcurrentHashMap<>();

    public ApplicationEventBus() {
    }

    /** Empfängt alle Ereignisse. */
    public void subscribe(Consumer<ApplicationEvent<?>> listener) {
        if (listener != null) {
            anyListeners.add(listener);
        }
    }

    /** Empfängt nur Ereignisse, die zu {@code clazz} passen. */
    public <T extends ApplicationEvent<?>> void subscribe(Class<T> clazz, Consumer<T> listener) {
        if (clazz == null || listener == null) {
            return;
        }
        typed.computeIfAbsent(clazz, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void unsubscribe(Consumer<ApplicationEvent<?>> listener) {
        anyListeners.remove(listener);
    }

    public <T extends ApplicationEvent<?>> void unsubscribe(Class<T> clazz, Consumer<T> listener) {
        if (clazz == null || listener == null) {
            return;
        }
        List<Consumer<?>> list = typed.get(clazz);
        if (list != null) {
            list.remove(listener);
        }
    }

    public void publish(ApplicationEvent<?> event) {
        if (event == null) {
            return;
        }
        for (Consumer<ApplicationEvent<?>> l : anyListeners) {
            try {
                l.accept(event);
            } catch (Throwable ignore) {
                // ein fehlerhafter Abonnent hält die übrigen nicht auf
            }
        }
        for (Map.Entry<Class<?>, List<Consumer<?>>> e : typed.entrySet()) {
            if (e.getKey().isInstance(event)) {
                for (Consumer<?> raw : e.getValue()) {
                    @SuppressWarnings("unchecked")
                    Consumer<ApplicationEvent<?>> l = (Consumer<ApplicationEvent<?>>) raw;
                    try {
                        l.accept(event);
                    } catch (Throwable ignore) {
                        // wie oben
                    }
                }
            }
        }
    }
}
