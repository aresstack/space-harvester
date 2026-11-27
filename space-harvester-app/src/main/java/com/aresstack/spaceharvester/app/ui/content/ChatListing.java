package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.project.ChatGroup;
import com.aresstack.spaceharvester.app.project.Project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Was die Chat-Liste zeigt (ADR 0043, Nachtrag F2): nur die Chats des aktiven Projekts, oben je Gruppe ihre Chats,
 * darunter die Chats ohne Gruppe. Jeder Chat steht genau einmal da. Ein Chat ohne oder mit unbekannter Projekt-ID gehört
 * zum Standardprojekt, eine unbekannte Gruppen-ID heißt „ohne Gruppe“. Reine Logik, Reihenfolge der Eingabe bleibt.
 *
 * @param grouped   je Gruppe des Projekts (in deren Reihenfolge, auch leere) ihre Chats
 * @param ungrouped die Chats des Projekts ohne Gruppe
 */
record ChatListing(Map<ChatGroup, List<ChatStore.Entry>> grouped, List<ChatStore.Entry> ungrouped) {

    static ChatListing of(Collection<ChatStore.Entry> all, String activeProjectId, Set<String> knownProjectIds,
                          List<ChatGroup> groups) {
        Map<ChatGroup, List<ChatStore.Entry>> grouped = new LinkedHashMap<>();
        Map<String, ChatGroup> byId = new LinkedHashMap<>();
        for (ChatGroup group : groups) {
            grouped.put(group, new ArrayList<>());
            byId.put(group.id(), group);
        }
        List<ChatStore.Entry> ungrouped = new ArrayList<>();
        for (ChatStore.Entry entry : all) {
            if (!projectOf(entry.record(), knownProjectIds).equals(activeProjectId)) {
                continue;
            }
            ChatGroup group = entry.record().groupId() == null ? null : byId.get(entry.record().groupId());
            (group == null ? ungrouped : grouped.get(group)).add(entry);
        }
        return new ChatListing(grouped, ungrouped);
    }

    /** Das Projekt eines Chats: seine Projekt-ID, wenn es das Projekt gibt, sonst das Standardprojekt. */
    static String projectOf(ChatRecord record, Set<String> knownProjectIds) {
        String id = record.projectId();
        return id != null && knownProjectIds.contains(id) ? id : Project.DEFAULT_ID;
    }

    /** Die Projekt-ID für einen neuen Chat im Projekt: im Standardprojekt keine, damit die Datei aussieht wie bisher. */
    static String idFor(Project project) {
        return project == null || project.isDefault() ? null : project.id();
    }
}
