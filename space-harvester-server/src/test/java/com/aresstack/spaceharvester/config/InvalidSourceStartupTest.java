package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.SpaceHarvesterApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Eine falsch konfigurierte Quelle verhindert den Start mit einer Meldung, die Quelle und Feld nennt. */
class InvalidSourceStartupTest {

    @Test
    void startFailsWithAllSourceProblems() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                .run("--server.port=0",
                        "--space-harvester.sources[0].id=wiki",
                        "--space-harvester.sources[0].type=confluence",
                        "--space-harvester.sources[0].auth.type=basic",
                        "--space-harvester.sources[0].auth.username=harvester")
                .close())
                .rootCause()
                .hasMessageContaining("Ungültige Quellenkonfiguration (space-harvester.sources)")
                .hasMessageContaining("sources[0] (id=wiki): base-url fehlt")
                .hasMessageContaining("sources[0] (id=wiki): auth.type basic braucht auth.username und auth.password");
    }
}
