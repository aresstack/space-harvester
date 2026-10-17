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
    static final ArchRule embeddingAdaptersKnowOnlyApplication = noClasses()
            .that().resideInAPackage("..spaceharvester.embedding..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..spaceharvester.api..", "..spaceharvester.source..", "..spaceharvester.output..",
                    "..spaceharvester.config..", "..spaceharvester.security..", "..spaceharvester.persistence..",
                    "org.springframework..")
            .because("das Modell steckt hinter dem Port EmbeddingProvider (ADR 0004)");

    @ArchTest
    static final ArchRule modelRuntimesStayInsideTheEmbeddingAdapter = noClasses()
            .that().resideOutsideOfPackage("..spaceharvester.embedding..")
            .should().dependOnClassesThat().resideInAnyPackage("ai.onnxruntime..", "ai.djl..")
            .because("ONNX Runtime und Tokenizer sind ein Detail des lokalen Modells (#89)");

    @ArchTest
    static final ArchRule copiedUpstreamCodeIsUsedOnlyBySourceAdapters = noClasses()
            .that().resideOutsideOfPackage("..spaceharvester.source..")
            .should().dependOnClassesThat().resideInAPackage("com.aresstack.enterpriseai..")
            .because("die Kopie aus enterprise-ai-sources bleibt hinter den Quellenadaptern; "
                    + "api, application und output arbeiten mit der eigenen, quellenunabhängigen SPI");

    @ArchTest
    static final ArchRule applicationDoesNotKnowPersistence = noClasses()
            .that().resideInAPackage("..spaceharvester.application..")
            .should().dependOnClassesThat().resideInAPackage("..spaceharvester.persistence..")
            .because("Anwendungsfälle arbeiten gegen Ports; die Persistenz setzt sie um");

    @ArchTest
    static final ArchRule persistenceKnowsOnlyApplication = noClasses()
            .that().resideInAPackage("..spaceharvester.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage("..spaceharvester.api..",
                    "..spaceharvester.source..", "..spaceharvester.output..", "..spaceharvester.config..",
                    "org.springframework..");

    @ArchTest
    static final ArchRule luceneStaysInsideItsAdapter = noClasses()
            .that().resideOutsideOfPackage("..spaceharvester.persistence.lucene..")
            .should().dependOnClassesThat().resideInAPackage("org.apache.lucene..")
            .because("der Index ist ein Detail der Persistenz (#26); Ports tragen keine Lucene-Typen");

    @ArchTest
    static final ArchRule delegatedTokenIsReadOnlyBySourceAdapters = noClasses()
            .that().resideOutsideOfPackage("..spaceharvester.source..")
            .should().callMethod(com.aresstack.spaceharvester.application.access.UserCredential.class, "token")
            .because("das durchgereichte Endnutzer-Token (#24) liest nur der Quellenadapter für den Aufruf der "
                    + "Quelle; alle anderen behandeln es als undurchsichtig (wie Adyton in Corenth)");
}
