package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import java.io.IOException;

/**
 * The one check whether a file may be acquired automatically, shared by inventory and navigation fill, so both decide
 * alike. Metadata first, a bounded sample only when the metadata do not decide:
 * <ul>
 *   <li>RECFM {@code U} (undefined record format, typically load libraries) is conservatively never acquired
 *       automatically; that does not claim every such dataset is a load library.</li>
 *   <li>With "program sources only" a sample must be recognized as COBOL, copybook or JCL.</li>
 *   <li>Otherwise a known fixed or variable record format suffices; without one a sample decides, and visibly binary
 *       content is not acquired.</li>
 * </ul>
 * Explicit access (opening, explicit indexing) does not pass through here and stays possible for any file.
 */
final class FtpSourceEligibility {

    /** Default of the source setting {@code sampleKilobytes}. */
    static final int DEFAULT_SAMPLE_KILOBYTES = 64;

    private final boolean programSourcesOnly;
    private final int sampleBytes;

    FtpSourceEligibility(boolean programSourcesOnly, int sampleBytes) {
        if (sampleBytes < 1) {
            throw new IllegalArgumentException("sampleBytes must be positive");
        }
        this.programSourcesOnly = programSourcesOnly;
        this.sampleBytes = sampleBytes;
    }

    boolean eligible(FtpClientSession session, String path) throws IOException {
        if (!session.mvs()) {
            return true;
        }
        String recordFormat = session.recordFormat(path);
        if (recordFormat.startsWith("U")) {
            return false;
        }
        if (!programSourcesOnly && !recordFormat.isEmpty()) {
            return true;
        }
        String sample = session.sampleText(path, sampleBytes);
        if (sample == null || looksBinary(sample)) {
            return false;
        }
        return !programSourcesOnly || FtpProgramClassifier.classify(sample) != FtpProgramClassifier.Kind.UNKNOWN;
    }

    /** Control characters other than line structure, or undecodable bytes: not source text. */
    static boolean looksBinary(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '�' || Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t' && c != '\f') {
                return true;
            }
        }
        return false;
    }
}
