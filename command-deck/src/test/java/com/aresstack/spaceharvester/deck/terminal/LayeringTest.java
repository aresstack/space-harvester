package com.aresstack.spaceharvester.deck.terminal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Ports und Adapter wie in Corenth: Fachmodell und Anwendungsfälle des Command Deck kennen weder Swing/AWT noch die
 * Emulationsbibliothek; nur der Adapter {@code ...terminal.openterm} spricht mit OpenTerm.
 */
class LayeringTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aresstack.spaceharvester.deck");

    @Test
    void theTerminalModelKnowsNoUiAndNoEmulator() {
        noClasses().that().resideInAnyPackage("com.aresstack.spaceharvester.deck.terminal",
                        "com.aresstack.spaceharvester.deck.access")
                .should().dependOnClassesThat().resideInAnyPackage("javax.swing..", "java.awt..", "com.ascert..")
                .check(classes);
    }

    @Test
    void onlyTheAdapterUsesOpenTerm() {
        noClasses().that().resideOutsideOfPackage("com.aresstack.spaceharvester.deck.terminal.openterm..")
                .should().dependOnClassesThat().resideInAPackage("com.ascert..")
                .check(classes);
    }
}
