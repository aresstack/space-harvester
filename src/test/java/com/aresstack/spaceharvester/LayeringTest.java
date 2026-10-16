package com.aresstack.spaceharvester;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hält die Abhängigkeitsrichtung aus {@code package-info.java} fest.
 */
@AnalyzeClasses(packages = "com.aresstack.spaceharvester", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringTest {

    @ArchTest
    static final ArchRule applicationIsFrameworkNeutral = noClasses()
            .that().resideInAPackage("..spaceharvester.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "io.swagger..", "jakarta.servlet..")
            .because("Anwendungsfälle werden in config verdrahtet und bleiben frameworkneutral");

    @ArchTest
    static final ArchRule applicationDoesNotKnowApiOrConfig = noClasses()
            .that().resideInAPackage("..spaceharvester.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..spaceharvester.api..", "..spaceharvester.config..", "..spaceharvester.security..");

    @ArchTest
    static final ArchRule apiUsesApplicationNotAdapters = noClasses()
            .that().resideInAPackage("..spaceharvester.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..spaceharvester.source..", "..spaceharvester.output..", "..spaceharvester.config..",
                    "..spaceharvester.security..")
            .because("Controller erhalten den AccessContext per Parameter und prüfen über den AccessGuard");

    @ArchTest
    static final ArchRule securityKnowsNeitherApiNorAdapters = noClasses()
            .that().resideInAPackage("..spaceharvester.security..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..spaceharvester.api..", "..spaceharvester.source..", "..spaceharvester.output..");

    @ArchTest
    static final ArchRule sourceKnowsNeitherApiNorOutput = noClasses()
            .that().resideInAPackage("..spaceharvester.source..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..spaceharvester.api..", "..spaceharvester.output..", "..spaceharvester.config..",
                    "..spaceharvester.security..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule outputKnowsNeitherApiNorSource = noClasses()
            .that().resideInAPackage("..spaceharvester.output..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..spaceharvester.api..", "..spaceharvester.source..", "..spaceharvester.config..",
                    "..spaceharvester.security..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule copiedUpstreamCodeIsUsedOnlyBySourceAdapters = noClasses()
            .that().resideOutsideOfPackage("..spaceharvester.source..")
            .should().dependOnClassesThat().resideInAPackage("com.aresstack.enterpriseai..")
            .because("die Kopie aus enterprise-ai-sources bleibt hinter den Quellenadaptern; "
                    + "api, application und output arbeiten mit der eigenen, quellenunabhängigen SPI");
}
