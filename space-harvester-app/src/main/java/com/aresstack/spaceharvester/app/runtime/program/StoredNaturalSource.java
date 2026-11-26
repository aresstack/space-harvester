package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.NdvSourceProvider;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Recover exact native NDV content using its separate content digest and object identity. */
public record StoredNaturalSource(String library, String name, String extension, String content) {
    private static final Set<String> TYPES = Set.of("NSP", "NSN", "NSS", "NSC", "NSL", "NSA", "NSG", "NSD",
            "NSM", "NSH", "NS7");

    public static Optional<StoredNaturalSource> read(PageSnapshot snapshot) {
        String library = snapshot.metadata().attributes().get("ndv.library");
        String resource = snapshot.metadata().resourceId();
        if (library == null || library.isBlank() || !resource.startsWith("ndv:")) return Optional.empty();
        int slash = resource.lastIndexOf('/');
        int dot = resource.lastIndexOf('.');
        if (slash < 0 || dot <= slash + 1 || !resource.substring(0, slash).endsWith("/" + library)) return Optional.empty();
        String extension = resource.substring(dot + 1).toUpperCase(Locale.ROOT);
        if (!TYPES.contains(extension)) return Optional.empty();
        String name = resource.substring(slash + 1, dot);
        if (!NdvSourceProvider.SOURCE_TEXT_FORMAT.equals(snapshot.payload().nativeFormat())) return Optional.empty();
        String expected = snapshot.metadata().attributes().get(NdvSourceProvider.SOURCE_HASH);
        // Inventory-only header refreshes can omit the download-time digest.
        // Native payload integrity is still represented by its content hash.
        if (expected != null && !("sha256:" + expected.toLowerCase(Locale.ROOT)).equals(snapshot.payload().rawSha256())) {
            return Optional.empty();
        }
        try {
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(snapshot.payload().bytes())).toString();
            return Optional.of(new StoredNaturalSource(library, name, extension, content));
        } catch (CharacterCodingException invalid) {
            return Optional.empty();
        }
    }
}
