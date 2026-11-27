package com.aresstack.knowledge4j.file;

import java.io.File;

/** A project file exists but cannot be read; the caller keeps it and blocks instead of starting over. */
public final class CorruptFileException extends Exception {

    private final File file;

    public CorruptFileException(File file, String message) {
        super(message);
        this.file = file;
    }

    public File getFile() {
        return file;
    }
}
