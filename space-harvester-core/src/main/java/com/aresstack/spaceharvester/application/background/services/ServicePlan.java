package com.aresstack.spaceharvester.application.background.services;

import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ergebnis von {@link ServicePlanner#start}.
 *
 * @param group        die angelegte Gruppe oder leer, wenn nichts zu tun war
 * @param notes        gelbe Hinweise, etwa bewusst ausgelassene Vorstufen; nie mit Seitentiteln
 * @param inaccessible Seiten der Menge ohne Zugriff des Aufrufers, nur als Zahl
 */
public record ServicePlan(Optional<BackgroundGroup> group, List<String> notes, int inaccessible) {

    public ServicePlan {
        Objects.requireNonNull(group, "group");
        notes = List.copyOf(notes);
    }
}
