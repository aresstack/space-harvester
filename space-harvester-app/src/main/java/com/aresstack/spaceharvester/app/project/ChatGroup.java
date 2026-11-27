package com.aresstack.spaceharvester.app.project;

import com.aresstack.spaceharvester.app.group.GroupColor;
import java.util.Objects;

/**
 * Eine farbige Gruppe von Chats innerhalb eines Projekts (ADR 0043, Nachtrag F2; Angelo, 27.11.2026: „farbig gruppieren
 * innerhalb eines Projekts“), wie eine Tabgruppe in Firefox. Ein Chat gehört zu höchstens einer Gruppe seines
 * Projekts ({@code ChatRecord#groupId}). Gruppen ordnen nur die Chat-Liste und haben sonst keine Wirkung.
 *
 * @param id    feste Kennung (UUID)
 * @param name  Anzeigename, darf mehrfach vorkommen
 * @param color die Farbe aus der festen Auswahl
 */
public record ChatGroup(String id, String name, GroupColor color) {

    public ChatGroup {
        Objects.requireNonNull(id, "id");
        name = name == null || name.isBlank() ? "Gruppe" : name.strip();
        color = color == null ? GroupColor.GRAU : color;
    }

    public ChatGroup withName(String newName) {
        return new ChatGroup(id, newName, color);
    }

    public ChatGroup withColor(GroupColor newColor) {
        return new ChatGroup(id, name, newColor);
    }
}
