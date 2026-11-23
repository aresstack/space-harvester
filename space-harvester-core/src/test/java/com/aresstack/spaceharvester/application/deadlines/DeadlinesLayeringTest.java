package com.aresstack.spaceharvester.application.deadlines;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Fristentscheidungen fallen nur im Core und nur im Evaluator (ADR 0035, Abschnitte 1 und 6). */
class DeadlinesLayeringTest {

    private static final JavaClasses CORE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aresstack.spaceharvester");

    @Test
    void onlyTheEvaluatorCreatesAssessments() {
        noClasses().that().doNotHaveSimpleName("DeadlineRuleEvaluator")
                .should().callConstructorWhere(target(owner(assignableTo(DeadlineAssessment.class))))
                .allowEmptyShould(true)
                .check(CORE);
    }

    @Test
    void deadlinesStayFreeOfUiAndInfrastructure() {
        noClasses().that().resideInAPackage("..application.deadlines..")
                .should().dependOnClassesThat().resideInAnyPackage("javax.swing..", "org.apache.lucene..",
                        "..source..", "..app..")
                .check(CORE);
    }
}
