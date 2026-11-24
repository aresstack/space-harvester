# ProLeap COBOL Parser

Upstream: https://github.com/uwol/proleap-cobol-parser

Pinned commit: `d1bfe75bdd6d480f70c74c6345bcc02610ac30d3` (POM version 4.0.0).
License: MIT; see LICENSE. Only upstream production Java sources and the two
ANTLR grammars are included. The upstream interpreter/transformer, upstream
test corpus and compiled binaries are not included.

The upstream Maven coordinates returned HTTP 404 from Maven Central on
2026-11-25. This module builds the source snapshot reproducibly with the
repository's Java 21 toolchain and ANTLR 4.13.2; upstream uses ANTLR 4.7.2.
The upstream src/main/antlr4 directory is copied to src/main/antlr for Gradle.
ANTLR generator stays on the build classpath; runtime uses ANTLR runtime and
SLF4J API from the shared version catalog. Preserve the original MIT notice
in the distributed resources.

To update, verify the new upstream commit and license, copy only src/main/java
and src/main/antlr4 plus LICENSE, inspect changes, regenerate parsers and run
the integration tests. Do not copy third-party test data without license review.

Issue: #1422.
