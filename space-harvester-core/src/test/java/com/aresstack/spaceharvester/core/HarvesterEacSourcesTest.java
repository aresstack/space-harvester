package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceType;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Quellen des Enterprise AI Client ohne eigenen Ordner (#187) sind als Typen {@code ftp}, {@code jes}, {@code ndv}
 * und {@code sharepoint} registriert: gültige Einstellungen öffnen je eine Partition ohne Verbindung, die Prüfung des
 * Upstream-Typs meldet ihre Probleme beim Start.
 */
class HarvesterEacSourcesTest {

    @TempDir
    Path data;

    @Test
    void theCopiedSourceTypesAreKnownWithoutOwnRegistration() {
        assertThat(ConfiguredSourceCatalog.TYPES).contains("ftp", "jes", "ndv", "sharepoint");
    }

    @Test
    void theHarvesterListsItsSourceTypesWithDisplayNames() {
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            assertThat(harvester.sourceTypes()).containsExactly(
                    new SourceType("confluence", "Confluence", false), new SourceType("files", "Ordner", false),
                    new SourceType("ftp", "FTP", false), new SourceType("jes", "JES", true),
                    new SourceType("ndv", "NDV", true), new SourceType("sharepoint", "SharePoint", false),
                    new SourceType("git", "Git-Repository", false));
        }
    }

    @Test
    void eachTypeOpensOnePartitionWithoutConnecting() {
        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("host-ftp", "ftp", "FTP", null, null,
                        Map.of("host", "mvs.example.org", "startPoints", "HLQ.DATA")))
                .source(new SourceSettingsConfig("host-jes", "jes", "JES", null, null,
                        Map.of("host", "mvs.example.org", "credentialRef", "keepass:jes")))
                .source(new SourceSettingsConfig("natural", "ndv", "NDV", null, null,
                        Map.of("host", "ndv.example.org", "credentialRef", "keepass:ndv", "startPoints", "MYLIB")))
                .source(new SourceSettingsConfig("team", "sharepoint", "Team", null, null,
                        Map.of("siteUrl", "https://sharepoint.example.org/sites/team")))
                .dataDirectory(data).build()) {
            for (String id : new String[] {"host-ftp", "host-jes", "natural", "team"}) {
                assertThat(harvester.sources().partitions(id)).extracting(Partition::key).containsExactly(id);
            }
        }
    }

    @Test
    void theUpstreamValidationRejectsIncompleteSettings() {
        SourceSettingsConfig jes = new SourceSettingsConfig("host-jes", "jes", "JES", null, null,
                Map.of("host", "mvs.example.org"));
        assertThatThrownBy(() -> Harvester.builder().source(jes).dataDirectory(data).build())
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .satisfies(e -> assertThat(((InvalidSourceConfigurationException) e).problems())
                        .anySatisfy(problem -> assertThat(problem).startsWith("sources[0] (id=host-jes)")
                                .contains("credentialRef")));
    }
}
