package com.aresstack.spaceharvester;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aresstack.spaceharvester.source.confluence.WebClientConfluenceTransport;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hält die Modulgrenze aus docs/MODULES.md fest (#100): Die Bibliothek kennt den Server nicht und kommt ohne Spring
 * Boot, Servlet und Spring Security aus. Die Schichtregeln innerhalb der Pakete prüft {@code LayeringTest} im Server
 * über beide Module.
 */
@AnalyzeClasses(packages = "com.aresstack.spaceharvester", importOptions = ImportOption.DoNotIncludeTests.class)
class CoreModuleTest {

    @ArchTest
    static final ArchRule coreDoesNotKnowTheServer = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..spaceharvester.api..", "..spaceharvester.config..", "..spaceharvester.security..")
            .because("die Abhängigkeit läuft nur vom Server zur Bibliothek");

    @ArchTest
    static final ArchRule coreIsNoSpringApplication = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.boot..", "org.springframework.context..", "org.springframework.beans..",
                    "org.springframework.security..", "jakarta.servlet..", "io.swagger..")
            .because("die Bibliothek läuft ohne ApplicationContext, Servlet-Container und REST");

    @ArchTest
    static final ArchRule springOnlyAsHttpClientOfTheAsyncTransport = noClasses()
            .that().doNotHaveFullyQualifiedName(WebClientConfluenceTransport.class.getName())
            .and().doNotHaveFullyQualifiedName(WebClientConfluenceTransport.class.getName() + "$Client")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .because("Spring WebClient ist die einzige dokumentierte Spring-Abhängigkeit des Core (docs/MODULES.md)");

    @ArchTest
    static final ArchRule noParserModelInTheCore = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("com.aresstack.spaceharvester.nlp..",
                    "org.maltparser..", "opennlp.tools.postag..", "opennlp.tools.lemmatizer..",
                    "opennlp.tools.tokenize..")
            .because("MaltParser und die OpenNLP-Modelle laufen nur in der Desktop-App auf Anforderung (#499, ADR 0024); "
                    + "der Core kennt nur den Port StatementAnalyzer");
}
