package com.aresstack.spaceharvester.application.reading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Was Confluence beim Rendern von außerhalb der Seite einbindet, fail closed. */
class ForeignContentTest {

    @Test
    void macrosShowingOnlyThePageItselfAreLocal() {
        ForeignContent content = ForeignContent.of("<ac:structured-macro ac:name=\"toc\"/>"
                + "<ac:structured-macro ac:name=\"info\"><ac:rich-text-body><p>Hinweis</p></ac:rich-text-body>"
                + "</ac:structured-macro><ac:image><ri:attachment ri:filename=\"bild.png\"/></ac:image>"
                + "<ac:link><ri:page ri:content-title=\"Andere Seite\"/></ac:link>"
                + "<ac:structured-macro ac:name=\"drawio\"><ac:parameter ac:name=\"pageId\">100</ac:parameter>"
                + "</ac:structured-macro>", "100");

        assertThat(content.present()).isFalse();
    }

    @Test
    void includesChildrenJiraAndUnknownMacrosAreForeign() {
        ForeignContent content = ForeignContent.of("<ac:structured-macro ac:name=\"include\"/>"
                + "<ac:structured-macro ac:name=\"children\"/><ac:structured-macro ac:name=\"JIRA\"/>"
                + "<ac:macro ac:name=\"my-user-macro\"/><ac:structured-macro ac:name=\"include\"/>", "100");

        assertThat(content.macros()).containsExactly("include", "children", "jira", "my-user-macro");
        assertThat(content.present()).isTrue();
    }

    @Test
    void referencesToOtherPagesAreForeign() {
        assertThat(ForeignContent.of("<ac:image><ri:attachment ri:filename=\"x.png\"><ri:page "
                + "ri:content-title=\"Geheim\"/></ri:attachment></ac:image>", "100").references()).isTrue();
        assertThat(ForeignContent.of("<ac:link><ri:content-entity ri:content-id=\"7\"/></ac:link>", "100")
                .references()).isTrue();
        assertThat(ForeignContent.of("<ac:structured-macro ac:name=\"view-file\"><ac:parameter ac:name=\"name\">"
                + "<ri:attachment ri:filename=\"a.pdf\"><ri:page ri:content-title=\"B\"/></ri:attachment>"
                + "</ac:parameter></ac:structured-macro>", "100").present()).isTrue();
        assertThat(ForeignContent.of("<ac:structured-macro ac:name=\"drawio\"><ac:parameter ac:name=\"pageId\">"
                + "200</ac:parameter></ac:structured-macro>", "100").references()).isTrue();
    }

    @Test
    void noStorageMeansNothingForeign() {
        assertThat(ForeignContent.of(null, "1")).isEqualTo(ForeignContent.NONE);
    }
}
