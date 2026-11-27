package com.aresstack.enterpriseai.browser.runtime;

import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserException;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;

/**
 * Stable chat id to persistent profile folder: {@code <root>/<chatId>}. Same chat id after an app restart, same
 * folder. Only plain ids are accepted, so no id can point outside the root.
 */
public final class BrowserProfileStore {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private final Path root;

    public BrowserProfileStore(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    /** The profile folder of the chat, created if missing. */
    public Path profileOf(ChatConversationId conversation) throws BrowserException {
        Path folder = folderOf(conversation);
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new BrowserException(BrowserException.Kind.START_FAILED,
                    "Browserprofil konnte nicht angelegt werden: " + e.getMessage(), e);
        }
        return folder;
    }

    /** Deletes the profile folder of the chat, if any. */
    public void delete(ChatConversationId conversation) throws BrowserException {
        Path folder = folderOf(conversation);
        if (!Files.exists(folder)) {
            return;
        }
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new BrowserException(BrowserException.Kind.FAILED,
                    "Browserprofil konnte nicht gelöscht werden: " + e.getMessage(), e);
        }
    }

    Path folderOf(ChatConversationId conversation) throws BrowserException {
        if (conversation == null || !SAFE_ID.matcher(conversation.value()).matches()) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Ungültige Chat-Kennung");
        }
        Path folder = root.resolve(conversation.value()).normalize();
        if (!folder.getParent().equals(root)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Ungültige Chat-Kennung");
        }
        return folder;
    }
}
