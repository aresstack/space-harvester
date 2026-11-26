package com.aresstack.spaceharvester.application.evidence;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Belege sind reine Werttypen: keine Ablage, kein Parser, keine KI, keine Oberfläche. */
class EvidenceLayeringTest {

    @Test
    void evidenceStaysPure() {
        // Nur das eigene Paket einlesen: Abhängigkeiten sieht ArchUnit trotzdem, und der Test-Heap des Core ist klein
        JavaClasses evidence = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aresstack.spaceharvester.application.evidence");
        noClasses().that().resideInAPackage("..application.evidence..")
                .should().dependOnClassesThat().resideInAnyPackage("..persistence..", "..source..",
                        "com.aresstack.spaceharvester.ai..", "..core..", "javax.swing..")
                .check(evidence);
    }
}
