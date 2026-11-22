package com.aresstack.spaceharvester.deck.files;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Ports und Adapter wie in Corenth: Fachmodell und Anwendungsfälle von Dateien und Editor kennen weder Swing/AWT noch
 * ein konkretes Dateisystem; nur die Adapter ({@code ...files.local}) greifen auf {@code java.nio.file} zu.
 */
class FilesLayeringTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aresstack.spaceharvester.deck");

    @Test
    void filesAndEditorKnowNoUi() {
        noClasses().that().resideInAnyPackage("com.aresstack.spaceharvester.deck.files..",
                        "com.aresstack.spaceharvester.deck.editor..")
                .should().dependOnClassesThat().resideInAnyPackage("javax.swing..", "java.awt..")
                .check(classes);
    }

    @Test
    void onlyAdaptersTouchTheFileSystem() {
        noClasses().that().resideInAnyPackage("com.aresstack.spaceharvester.deck.files",
                        "com.aresstack.spaceharvester.deck.editor")
                .should().dependOnClassesThat().resideInAnyPackage("java.nio.file..", "java.io..")
                .check(classes);
    }

    @Test
    void theEditorDoesNotDependOnTheFileBrowser() {
        noClasses().that().resideInAPackage("com.aresstack.spaceharvester.deck.editor..")
                .should().dependOnClassesThat().resideInAPackage("com.aresstack.spaceharvester.deck.files..")
                .check(classes);
    }
}
