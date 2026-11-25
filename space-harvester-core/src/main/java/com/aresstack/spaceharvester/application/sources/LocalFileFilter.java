package com.aresstack.spaceharvester.application.sources;

/** Keep the saved pattern and the effective filter independent. */
public final class LocalFileFilter {
    public static final String DEFAULT_PATTERNS = "*.md, *.txt, *.pdf, *.doc, *.docx, *.html, *.htm, *.one, "
            + "*.xls, *.xlsx, *.ppt, *.pptx, *.csv, *.tsv, *.odt, *.ods, *.odp, *.rtf, *.eml, *.msg, "
            + "*.png, *.jpg, *.jpeg, *.gif, *.bmp, *.webp, *.tif, *.tiff, *.svg, "
            + "*.mp4, *.m4v, *.webm, *.mov, *.mkv, *.avi, *.wmv, *.mpg, *.mpeg";

    private LocalFileFilter() {
    }

    /** Preserve legacy explicit patterns unless the user has selected a filter state. */
    public static boolean enabled(String patterns, String configured) {
        return configured == null ? patterns != null && !patterns.isBlank() : Boolean.parseBoolean(configured);
    }
}
