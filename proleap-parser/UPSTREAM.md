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

The SHA-256 manifest covers the 1,122 imported files (including LICENSE),
verified against the pinned upstream checkout. Hashes normalize CRLF to LF.
The Gradle task `verifyUpstreamCopy` checks for modified, missing and additional
files before compiling; regenerate the manifest from the verified upstream
snapshot when updating the import.

The local build-time overlay `ibm-replacement.gradle` derives the grammar in
the build directory and adds explicit LEADING/TRAILING partial-word rules and
same-line replacement clauses. It does not modify the verified upstream
files. The adapter implements replacement and source mapping; see IBM
Enterprise COBOL comparison and replacement rules:
https://www.ibm.com/docs/en/cobol-zos/6.3.0?topic=statement-comparison-replacement-rules
and https://www.ibm.com/docs/en/cobol-zos/6.4.0?topic=statements-replace-statement.
This is not a claim of complete IBM compiler conformance.

Issue: #1422.
