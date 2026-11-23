package com.aresstack.spaceharvester.application.deadlines;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fristentscheidungen fallen nur im Core und nur im Evaluator (ADR 0035, Abschnitte 1 und 6). Dieselbe Klassenmenge
 * wie {@code CoreModuleTest}, damit ArchUnit den Import aus seinem Cache teilt: Ein eigener statischer Import des ganzen
 * Core blieb bis zum Ende der Test-JVM im Speicher und brachte sie zum OutOfMemoryError.
 */
@AnalyzeClasses(packages = "com.aresstack.spaceharvester", importOptions = ImportOption.DoNotIncludeTests.class)
class DeadlinesLayeringTest {

    @ArchTest
    static final ArchRule onlyTheEvaluatorCreatesAssessments = noClasses()
            .that().doNotHaveSimpleName("DeadlineRuleEvaluator")
            .should().callConstructorWhere(target(owner(assignableTo(DeadlineAssessment.class))))
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule deadlinesStayFreeOfUiAndInfrastructure = noClasses()
            .that().resideInAPackage("..application.deadlines..")
            .should().dependOnClassesThat().resideInAnyPackage("javax.swing..", "org.apache.lucene..",
                    "..source..", "..app..");
}
