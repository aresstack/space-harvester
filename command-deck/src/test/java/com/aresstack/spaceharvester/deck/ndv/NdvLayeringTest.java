package com.aresstack.spaceharvester.deck.ndv;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Ports und Adapter wie in Corenth: Das NDV-Fachmodell kennt weder Swing/AWT noch das PAL-Protokoll; nur der Adapter
 * {@code ...ndv.pal} spricht mit der PAL-Schicht aus MainframeMate.
 */
class NdvLayeringTest {

    private static final String PAL = "com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv..";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aresstack.spaceharvester.deck");

    @Test
    void theNdvModelKnowsNoUiAndNoProtocol() {
        noClasses().that().resideInAnyPackage("com.aresstack.spaceharvester.deck.ndv",
                        "com.aresstack.spaceharvester.deck.ndv.analysis..")
                .should().dependOnClassesThat().resideInAnyPackage("javax.swing..", "java.awt..", PAL)
                .check(classes);
    }

    @Test
    void theAnalysisKnowsNoFilesAndTheFileAdapterNoUi() {
        noClasses().that().resideInAPackage("com.aresstack.spaceharvester.deck.ndv.analysis..")
                .should().dependOnClassesThat().resideInAnyPackage("java.nio.file..",
                        "com.aresstack.spaceharvester.deck.ndv.cache..", "com.aresstack.spaceharvester.deck.ndv.pal..")
                .check(classes);
        noClasses().that().resideInAPackage("com.aresstack.spaceharvester.deck.ndv.cache..")
                .should().dependOnClassesThat().resideInAnyPackage("javax.swing..", "java.awt..", PAL)
                .check(classes);
    }

    @Test
    void onlyTheAdapterSpeaksPal() {
        noClasses().that().resideOutsideOfPackage("com.aresstack.spaceharvester.deck.ndv.pal..")
                .should().dependOnClassesThat().resideInAPackage(PAL)
                .check(classes);
    }
}
