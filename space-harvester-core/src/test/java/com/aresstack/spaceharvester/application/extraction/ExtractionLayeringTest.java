package com.aresstack.spaceharvester.application.extraction;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Die Extraktion liefert nur Dokumentfakten: Sie kennt weder Fristprüfung noch Regeln, noch Oberfläche. */
class ExtractionLayeringTest {

    private static final JavaClasses CORE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aresstack.spaceharvester");

    @Test
    void extractionNeverDependsOnDeadlines() {
        noClasses().that().resideInAPackage("..application.extraction..")
                .should().dependOnClassesThat().resideInAnyPackage("..application.deadlines..", "javax.swing..")
                .check(CORE);
    }
}
