package com.aresstack.spaceharvester.provingground.recorder;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * The ONLY place that touches Playwright internals: the original Playwright recorder over the driver protocol.
 *
 * <p>Playwright Core (the copy inside playwright4j) has a programmatic recorder: {@code BrowserContext.enableRecorder}
 * with {@code recorderMode: "api"} records the user's actions with Playwright's own locator generator and input
 * merging and reports each one as a {@code recorderEvent} ({@code actionAdded}, {@code actionUpdated},
 * {@code signalAdded}) carrying Playwright's {@code ActionInContext}. The Playwright Java API has no public call for
 * it, so this class
 * <ul>
 *   <li>sends {@code enableRecorder}/{@code disableRecorder} through the package-private
 *   {@code ChannelOwner.sendMessage} of {@code com.microsoft.playwright.impl}, and</li>
 *   <li>reads the {@code recorderEvent} messages of the context from the connection's {@code Transport} (a public
 *   interface of the same package) by wrapping it; every message still reaches Playwright unchanged.</li>
 * </ul>
 *
 * <p>Version dependency: Playwright Java and Playwright Core 1.62.0 (protocol {@code BrowserContextEnableRecorderParams},
 * {@code BrowserContextRecorderEventEvent}). The protocol is marked internal; a new Playwright version is checked by
 * {@code RecorderBridgeContractTest} and the live test before it is taken over.</p>
 *
 * <p>Events arrive on the thread that is inside a Playwright call (like every Playwright event), before Playwright
 * dispatches the message; the listener must not call Playwright itself, it only queues.</p>
 */
final class RecorderBridge implements AutoCloseable {

    /** Protocol names of Playwright Core 1.62. */
    static final String ENABLE = "enableRecorder";
    static final String DISABLE = "disableRecorder";
    static final String EVENT = "recorderEvent";

    private static final String IMPL = "com.microsoft.playwright.impl.";

    private final BrowserContext context;
    private final Object connection;
    private final Field transportField;
    private final Object originalTransport;
    private final Object wrappedTransport;

    private RecorderBridge(BrowserContext context, Object connection, Field transportField, Object originalTransport,
                           Object wrappedTransport) {
        this.context = context;
        this.connection = connection;
        this.transportField = transportField;
        this.originalTransport = originalTransport;
        this.wrappedTransport = wrappedTransport;
    }

    /** Starts listening to the recorder events of {@code context}; {@code listener} gets each event's params. */
    static RecorderBridge attach(BrowserContext context, Consumer<JsonObject> listener) {
        try {
            Class<?> channelOwner = Class.forName(IMPL + "ChannelOwner");
            Object connection = field(channelOwner, "connection").get(context);
            String contextGuid = guid(context);
            Field transportField = field(connection.getClass(), "transport");
            Object original = transportField.get(connection);
            Class<?> transport = Class.forName(IMPL + "Transport");
            InvocationHandler handler = (proxy, method, args) -> {
                Object result;
                try {
                    result = method.invoke(original, args);
                } catch (InvocationTargetException failed) {
                    throw failed.getCause();
                }
                if ("poll".equals(method.getName()) && result instanceof JsonObject message
                        && message.has("method") && EVENT.equals(message.get("method").getAsString())
                        && message.has("guid") && contextGuid.equals(message.get("guid").getAsString())
                        && message.has("params")) {
                    listener.accept(message.getAsJsonObject("params").deepCopy());
                }
                return result;
            };
            Object wrapped = Proxy.newProxyInstance(transport.getClassLoader(), new Class<?>[]{transport}, handler);
            transportField.set(connection, wrapped);
            return new RecorderBridge(context, connection, transportField, original, wrapped);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            throw unsupported(failed);
        }
    }

    /** {@code BrowserContext.enableRecorder} with the given protocol params. */
    void enable(JsonObject params) {
        send(ENABLE, params);
    }

    /** {@code BrowserContext.disableRecorder}: the recorder stops recording (mode none). */
    void disable() {
        send(DISABLE, new JsonObject());
    }

    /** The protocol guid of a Playwright object (a page in a recorder event is named by it). */
    static String guid(Object playwrightObject) {
        try {
            return (String) field(Class.forName(IMPL + "ChannelOwner"), "guid").get(playwrightObject);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            throw unsupported(failed);
        }
    }

    /** The page of {@code context} with protocol guid {@code guid}, or {@code null}. */
    static Page page(BrowserContext context, String guid) {
        for (Page page : context.pages()) {
            if (guid(page).equals(guid)) {
                return page;
            }
        }
        return null;
    }

    private void send(String method, JsonObject params) {
        try {
            Class<?> channelOwner = Class.forName(IMPL + "ChannelOwner");
            Method send = channelOwner.getDeclaredMethod("sendMessage", String.class, JsonObject.class, Double.class);
            send.setAccessible(true);
            // The protocol's "no timeout": the recorder call does not depend on the page.
            send.invoke(context, method, params, 0d);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof PlaywrightException playwright) {
                throw playwright;
            }
            throw unsupported(failed.getCause());
        } catch (ReflectiveOperationException | RuntimeException failed) {
            throw unsupported(failed);
        }
    }

    /** Gives the connection its own transport back (only if nobody replaced ours meanwhile). */
    @Override
    public void close() {
        try {
            if (transportField.get(connection) == wrappedTransport) {
                transportField.set(connection, originalTransport);
            }
        } catch (IllegalAccessException ignored) {
            // the field was accessible when attaching
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException notHere) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static UnsupportedOperationException unsupported(Throwable cause) {
        return new UnsupportedOperationException("The Playwright recorder is not reachable with this Playwright "
                + "version (built for Playwright Java/Core 1.62.0): " + cause, cause);
    }
}
