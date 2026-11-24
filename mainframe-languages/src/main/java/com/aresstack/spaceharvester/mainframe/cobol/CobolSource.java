package com.aresstack.spaceharvester.mainframe.cobol;

import java.util.Objects;

/** Identify source text independently of the transport used to obtain it. */
public record CobolSource(String name, String content, Format format) {
    public enum Format { FIXED, VARIABLE, TANDEM }

    public CobolSource {
        name = name == null ? "" : name;
        content = content == null ? "" : content;
        Objects.requireNonNull(format, "format");
    }

    public static CobolSource fixed(String name, String content) {
        return new CobolSource(name, content, Format.FIXED);
    }
}
