package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.astu.source.SourceSettings;

import java.util.List;

/**
 * Der abstrakte Quellen-Port, über den ein Adapter seinen Quelltyp anbietet: Beschreibung samt Eingabefeldern,
 * Prüfung der Einstellungen und das Öffnen der konfigurierten Quelle. Die Composition Root registriert je Adapter
 * genau einen Provider; der Use Case {@code application.source.KnowledgeSourceManagement} und der Dialog
 * „+ Quelle“ kennen nur diesen Port und verzweigen nie nach dem Typ. Neue Quellen (SharePoint, Mail, FTP ...)
 * kommen als weiterer Provider hinzu, ohne Kern oder Oberfläche zu ändern.
 *
 * <p>Die Schlüssel in {@link SourceSettings} gehören dem Adapter; serialisiert werden sie unverändert als
 * {@code source.<n>.<schlüssel>}. Prüfen und Öffnen berühren nie das Netz und lesen nie Secrets.
 */
public interface KnowledgeSourceProvider {

    /** Typ, Anzeigename und Eingabefelder. */
    KnowledgeSourceType type();

    /**
     * Probleme der Einstellungen als „Feld: Erwartung“, nie mit Werten; leer, wenn sie sich öffnen lassen.
     */
    List<String> validate(SourceSettings settings);

    /** Crawl-Umfang der Indexierung; nur für Einstellungen ohne {@link #validate Probleme}. */
    SourceScope scope(SourceSettings settings);

    /**
     * Der kanonische Endpoint der Quelle ohne Scheme ({@link SourceLocators}), z. B. {@code cf.intern/confluence}
     * oder {@code host.example:21}; leer, solange die Einstellungen ihn nicht hergeben (etwa Adresse nur im
     * KeePass-Eintrag). Daraus entstehen die Quellenadresse ({@link KnowledgeSourceType#addressFor}) und
     * die Ressourcen-IDs {@code <scheme>:<locator>/<ziel>}; Titel und KeePass-Verweis gehören nie dazu.
     *
     * <p>Vorgabe für Adapter ohne eigene Regel: das Adressfeld des Typs ({@link KnowledgeSourceType#summaryKey()})
     * über {@link SourceLocators#of}. Neue Adapter liefern so nur ihr Scheme.
     */
    default String locator(SourceSettings settings) {
        String key = type().summaryKey();
        return key.isEmpty() || settings == null ? "" : SourceLocators.of(settings.get(key));
    }

    /**
     * Baut die Quelle (ohne Netzwerkzugriff).
     *
     * @throws IllegalArgumentException wenn {@link #validate} Probleme meldet
     */
    KnowledgeSourcePort open(SourceAddress sourceId, SourceSettings settings);
}
