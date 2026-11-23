package com.aresstack.spaceharvester.mainframe;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Parser, Modell, Gliederung, Aufrufbaum und Diagramm-Erzeugung kommen ohne Swing und RSyntaxTextArea aus; nur ...syntax hängt daran. */
class LanguagesLayeringTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aresstack.spaceharvester.mainframe");

    @Test
    void parsersHaveNoUi() {
        noClasses().that().resideInAnyPackage("..mainframe.parser..", "..mainframe.model..", "..mainframe.outline..",
                        "..mainframe.calltree..", "..mainframe.diagram..")
                .should().dependOnClassesThat().resideInAnyPackage("javax.swing..", "java.awt..", "org.fife..",
                        "..mainframe.syntax..")
                .check(classes);
    }
}
