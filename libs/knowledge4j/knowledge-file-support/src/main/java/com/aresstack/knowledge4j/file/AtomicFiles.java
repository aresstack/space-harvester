package com.aresstack.knowledge4j.file;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** UTF-8 file helpers for crash-safe writes: temp file, flushed to disk, then renamed over the target. */
public final class AtomicFiles {

    public static final Charset UTF8 = Charset.forName("UTF-8");

    private AtomicFiles() {
    }

    /** Write {@code content} atomically; the temp file is flushed to disk before the rename. */
    public static void write(File target, String content) throws IOException {
        File dir = target.getAbsoluteFile().getParentFile();
        if (dir != null) {
            Files.createDirectories(dir.toPath());
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(content.getBytes(UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Keep an unreadable file for repair: copy it next to itself as {@code <name>.corrupt-<time>} and
     * leave the original in place, so a later open still sees it and never starts over.
     * @return the copy, or {@code null} when even the copy failed.
     */
    public static File keepAside(File file) {
        File copy = new File(file.getAbsoluteFile().getParentFile(),
                file.getName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return copy;
        } catch (IOException failed) {
            return null;
        }
    }

    /** Move an unreadable file aside as {@code <name>.corrupt-<time>}; @return the new file or {@code null}. */
    public static File moveAside(File file) {
        File moved = new File(file.getAbsoluteFile().getParentFile(),
                file.getName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), moved.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return moved;
        } catch (IOException failed) {
            return null;
        }
    }
}
