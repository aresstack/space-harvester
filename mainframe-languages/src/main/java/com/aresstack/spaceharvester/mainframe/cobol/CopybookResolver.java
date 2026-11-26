package com.aresstack.spaceharvester.mainframe.cobol;

import java.io.IOException;
import java.util.Optional;

/** Resolve a copybook in the context of its including source. */
@FunctionalInterface
public interface CopybookResolver {
    CopybookResolver NONE = (source, name, library) -> Optional.empty();

    Optional<CobolSource> resolve(CobolSource includingSource, String name, String library) throws IOException;
}
