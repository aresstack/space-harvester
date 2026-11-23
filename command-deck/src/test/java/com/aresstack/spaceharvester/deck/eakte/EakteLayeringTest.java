package com.aresstack.spaceharvester.deck.eakte;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Ports und Adapter wie in Corenth: Das Fachmodell der E-Akte kennt weder Swing noch HTTP noch HTML. */
class EakteLayeringTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aresstack.spaceharvester.deck");

    @Test
    void theEakteModelKnowsNoUiNoHttpAndNoHtml() {
        noClasses().that().resideInAPackage("com.aresstack.spaceharvester.deck.eakte")
                .should().dependOnClassesThat().resideInAnyPackage("javax.swing..", "java.awt..", "java.net.http..",
                        "org.jsoup..", "com.aresstack.spaceharvester.deck.eakte.http..")
                .check(classes);
    }
}
