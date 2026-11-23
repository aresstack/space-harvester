package com.aresstack.spaceharvester.deck.betaview;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Ports und Adapter wie in Corenth: Fachmodell und Anwendungsfälle von BetaView kennen weder Swing/AWT noch HTTP oder
 * HTML; nur der Adapter {@code ...betaview.http} spricht mit dem Server und liest dessen Seiten.
 */
class BetaViewLayeringTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aresstack.spaceharvester.deck");

    @Test
    void theBetaViewModelKnowsNoUiNoHttpAndNoHtml() {
        noClasses().that().resideInAPackage("com.aresstack.spaceharvester.deck.betaview")
                .should().dependOnClassesThat().resideInAnyPackage("javax.swing..", "java.awt..", "java.net.http..",
                        "org.jsoup..", "com.aresstack.spaceharvester.deck.betaview.http..")
                .check(classes);
    }

    @Test
    void onlyTheHttpAdaptersReadHtml() {
        noClasses().that().resideOutsideOfPackages("com.aresstack.spaceharvester.deck.betaview.http..",
                        "com.aresstack.spaceharvester.deck.eakte.http..")
                .should().dependOnClassesThat().resideInAPackage("org.jsoup..")
                .check(classes);
    }

    @Test
    void terminalAndBetaViewStayApart() {
        noClasses().that().resideInAPackage("com.aresstack.spaceharvester.deck.betaview..")
                .should().dependOnClassesThat().resideInAPackage("com.aresstack.spaceharvester.deck.terminal..")
                .check(classes);
    }
}
