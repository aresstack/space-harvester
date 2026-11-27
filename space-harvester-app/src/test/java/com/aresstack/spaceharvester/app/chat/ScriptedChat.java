package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** Ein Chat-Modell nach Drehbuch: je Anfrage der nächste Schritt; merkt sich die Anfragen. */
final class ScriptedChat implements AiChat {
    private final List<BiConsumer<Request, Listener>> script;
    final List<Request> requests = new CopyOnWriteArrayList<>();

    ScriptedChat(List<BiConsumer<Request, Listener>> script) {
        this.script = script;
    }

    @Override
    public Task stream(Request request, Listener listener) {
        requests.add(request);
        script.get(requests.size() - 1).accept(request, listener);
        return () -> { };
    }
}
