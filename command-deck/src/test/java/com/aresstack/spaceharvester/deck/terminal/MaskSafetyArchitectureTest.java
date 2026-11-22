package com.aresstack.spaceharvester.deck.terminal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Die Sicherheitsgrenze des KI-Maskenassistenten (#1151) im Code erzwungen: Weder der Anwendungsfall noch sein Port
 * noch dessen OpenTerm-Adapter kommen an eine Taste, die etwas an den Host schickt.
 */
class MaskSafetyArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aresstack.spaceharvester.deck");

    @Test
    void theAssistantAndItsPortKnowNoHostKeys() {
        noClasses().that().haveSimpleNameStartingWith("Mask").or().haveSimpleName("TerminalMask")
                .or().haveSimpleName("CursorMove").or().haveSimpleName("OpenTermMask")
                .should().dependOnClassesThat().haveSimpleName("AidKey")
                .orShould().dependOnClassesThat().haveSimpleName("TerminalKeyboard")
                .orShould().dependOnClassesThat().haveSimpleName("OpenTermScreen")
                .orShould().dependOnClassesThat().haveSimpleName("KeyHandler")
                .check(classes);
    }

    @Test
    void theMaskAdapterNeverPressesAnAidKey() {
        noClasses().that().haveSimpleName("OpenTermMask")
                .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("Terminal.Fkey oder doKeyAction") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        String name = call.getTarget().getName();
                        return name.equals("Fkey") || name.equals("doKeyAction") || name.equals("doKeyPress");
                    }
                })
                .check(classes);
    }
}
