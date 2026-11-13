package com.aresstack.spaceharvester.document;

import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;

import java.util.Arrays;

/**
 * Space Harvester: the one Flexmark parser setup of the export writers (Markdown, Word, PDF).
 *
 * <p>The converters write a link or image target with spaces in angle brackets, as CommonMark allows
 * ({@code ![Plan](<attachment:Entwicklung MoeVe.JPG>)}, {@code <drawio:Stakeholder MoeVe>}). Flexmark 0.62 follows
 * an older spec and takes no space inside the brackets unless {@link Parser#SPACE_IN_LINK_URLS} is set; without it
 * such a figure is plain text, so the writers neither embed it nor report it.</p>
 */
final class ExportMarkdownParser {

    private ExportMarkdownParser() {
    }

    static Parser create(Extension... extensions) {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(extensions));
        options.set(Parser.SPACE_IN_LINK_URLS, true);
        return Parser.builder(options).build();
    }
}
