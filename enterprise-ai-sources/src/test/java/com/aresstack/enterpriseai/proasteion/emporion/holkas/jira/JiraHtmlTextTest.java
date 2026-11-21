package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JiraHtmlTextTest {

    @Test
    public void rendersHeadingsListsTablesAndCode() {
        String text = JiraHtmlText.toText("<h2>Schritte</h2><p>Erst <b>eins</b>,<br>dann zwei.</p>"
                + "<ol><li>Anmelden</li><li>Prüfen</li></ol>"
                + "<table><tbody><tr><th>Feld</th><th>Wert</th></tr><tr><td>Status</td><td>Open</td></tr></tbody></table>"
                + "<pre class=\"code\">int x = 1;\n</pre><img src=\"x.png\"><script>bad()</script>");
        assertEquals("## Schritte\n\nErst eins,\ndann zwei.\n\n1. Anmelden\n2. Prüfen\n\n| Feld | Wert |\n| Status | Open |\n\n"
                + "```\nint x = 1;\n```", text);
    }

    @Test
    public void emptyAndNullAreEmpty() {
        assertEquals("", JiraHtmlText.toText(null));
        assertEquals("", JiraHtmlText.toText("<p>  </p>"));
    }
}
