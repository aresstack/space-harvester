package com.aresstack.spaceharvester.application.extraction;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Die Extraktion liefert nur Dokumentfakten: Sie kennt weder Fristprüfung noch Regeln, noch Oberfläche. */
class ExtractionLayeringTest {

    @Test
    void extractionNeverDependsOnDeadlines() {
        // Nur das eigene Paket einlesen: Abhängigkeiten sieht ArchUnit trotzdem, und der Test-Heap des Core ist klein
        JavaClasses extraction = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aresstack.spaceharvester.application.extraction");
        noClasses().that().resideInAPackage("..application.extraction..")
                .should().dependOnClassesThat().resideInAnyPackage("..application.deadlines..", "javax.swing..")
                .check(extraction);
    }
}
