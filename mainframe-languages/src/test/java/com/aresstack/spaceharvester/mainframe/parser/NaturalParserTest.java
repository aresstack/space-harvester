package com.aresstack.spaceharvester.mainframe.parser;

import com.aresstack.spaceharvester.mainframe.dependency.NaturalDependencyService;
import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one Natural parser shared by outline, editor relations, NDV dependency graph and stored analysis (#1486):
 * behavior of both former copies is kept.
 */
class NaturalParserTest {

    @Test
    void inlineScopesOnTheDefineDataLineAndDeepDeclarationsAreBothKept() {
        var model = new NaturalParser().parse("""
                DEFINE DATA PARAMETER USING KASSEP01 LOCAL
                1 #KUNDE
                  2 #ADRESSE
                    3 #ORT (A20)
                END-DEFINE
                END
                """, "TEST");
        var scopes = model.getElements().stream().filter(element -> element.getType() == JclElementType.NAT_PARAMETER
                || element.getType() == JclElementType.NAT_LOCAL).toList();
        assertThat(scopes).extracting(JclElement::getName).containsExactly("PARAMETER", "LOCAL");
        assertThat(scopes.getFirst().getParameter("USING")).isEqualTo("KASSEP01");
        var local = scopes.get(1);
        var kunde = local.getChildren().getFirst();
        assertThat(kunde.getName()).isEqualTo("#KUNDE");
        var adresse = kunde.getChildren().getFirst();
        assertThat(adresse.getName()).isEqualTo("#ADRESSE");
        assertThat(adresse.getChildren()).extracting(JclElement::getName).containsExactly("#ORT");
    }

    @Test
    void singleLineDefineDataEndsTheBlock() {
        var model = new NaturalParser().parse("define data local using lagerl02 end-define\nCALLNAT 'LAGERN01'\nEND\n", "T");
        assertThat(model.getElements()).anyMatch(element -> element.getType() == JclElementType.NAT_CALLNAT
                && "LAGERN01".equals(element.getParameter("TARGET")));
    }

    @Test
    void commentMarkersInsideLiteralsAreNotComments() {
        var model = new NaturalParser().parse("""
                DEFINE DATA LOCAL
                1 #TEXT (A10) INIT <'A/*B'>
                1 #NEXT (A1) /* real comment
                END-DEFINE
                END
                """, "T");
        assertThat(model.getElements()).extracting(JclElement::getName).contains("#TEXT", "#NEXT");
    }

    @Test
    void dependenciesNameLiteralTargetsButNeverVariables() {
        var result = new NaturalDependencyService().analyze("""
                DEFINE DATA LOCAL USING LAGERL01
                END-DEFINE
                CALLNAT 'LAGERN01'
                CALLNAT #ZIEL
                FETCH 'MENU'
                CALL 'COBPGM'
                PERFORM PRUEFEN
                INCLUDE LAGERC01
                END
                """, "TEST");
        assertThat(result.getAllDependencies()).extracting(NaturalDependencyService.Dependency::getTargetName)
                .contains("LAGERL01", "LAGERN01", "MENU", "COBPGM", "PRUEFEN", "LAGERC01")
                .doesNotContain("#ZIEL");
        // The structural analysis still reports the dynamic call as dynamic.
        var analysis = new NaturalAnalyzer().analyze("LIB", "TEST", "NSP", "CALLNAT #ZIEL\nEND\n");
        assertThat(analysis.dependencies()).anyMatch(dependency -> dependency.kind() == NaturalAnalysis.Kind.CALLNAT
                && dependency.resolution() == NaturalAnalysis.Resolution.DYNAMIC);
        assertThat(List.of(NaturalDependencyService.DependencyKind.values()))
                .allSatisfy(kind -> assertThat(kind.getDisplayLabel()).matches("[\\p{L}\\p{N} ()/_-]+"));
    }
}
