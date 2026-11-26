package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads one line of an MVS dataset list ({@code LIST 'HLQ.*'}):
 * {@code Volume Unit Referred Ext Used Recfm Lrecl BlkSz Dsorg Dsname}. Shared by the inventory, the eligibility check
 * and the Command Deck's detail view, so there is one reading of these columns.
 */
public final class MvsDatasetListing {

    /** One dataset line; unknown values are empty strings or {@code 0}. */
    public static final class Dataset {
        private final String name;
        private final String volume;
        private final String recordFormat;
        private final int recordLength;
        private final int blockSize;
        private final String organization;
        private final String referred;
        private final boolean migrated;

        Dataset(String name, String volume, String recordFormat, int recordLength, int blockSize, String organization,
                String referred, boolean migrated) {
            this.name = name;
            this.volume = volume;
            this.recordFormat = recordFormat;
            this.recordLength = recordLength;
            this.blockSize = blockSize;
            this.organization = organization;
            this.referred = referred;
            this.migrated = migrated;
        }

        /** Fully qualified dataset name without quotes, upper case. */
        public String name() {
            return name;
        }

        public String volume() {
            return volume;
        }

        /** RECFM such as {@code FB}, {@code VB} or {@code U}; empty when the host did not report it. */
        public String recordFormat() {
            return recordFormat;
        }

        public int recordLength() {
            return recordLength;
        }

        public int blockSize() {
            return blockSize;
        }

        /** DSORG such as {@code PO} or {@code PS}; empty when unknown. */
        public String organization() {
            return organization;
        }

        /** Referred date as listed ({@code yyyy/MM/dd}); empty when unknown. */
        public String referred() {
            return referred;
        }

        /** Migrated or archived: attributes are not known without recall. */
        public boolean migrated() {
            return migrated;
        }
    }

    private static final Pattern RECFM = Pattern.compile("^[FVU]B?S?A?M?$");
    private static final Pattern NUMBER = Pattern.compile("^\\d+$");
    private static final Pattern DSORG = Pattern.compile("^(PO|PS|PO-E|VS|DA|IS|\\?\\?)$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}/\\d{2}/\\d{2}$");

    private MvsDatasetListing() {
    }

    /** The dataset of this line, or {@code null} for the heading and unreadable lines. */
    public static Dataset parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String[] tokens = raw.trim().split("\\s+");
        if (tokens.length < 2 || tokens[0].equalsIgnoreCase("Volume")) {
            return null;
        }
        String name = MvsQuoteNormalizer.unquote(tokens[tokens.length - 1]).toUpperCase(Locale.ROOT);
        if (tokens[0].equalsIgnoreCase("Migrated") || tokens[0].equalsIgnoreCase("ARCIVE")) {
            return new Dataset(name, "", "", 0, 0, "", "", true);
        }
        String organization = "";
        String recordFormat = "";
        int recordLength = 0;
        int blockSize = 0;
        String referred = "";
        int last = tokens.length - 2;
        if (last >= 0 && DSORG.matcher(tokens[last]).matches()) {
            organization = tokens[last];
            last--;
        }
        // From the right: BlkSz, Lrecl, Recfm.
        if (last >= 2 && NUMBER.matcher(tokens[last]).matches() && NUMBER.matcher(tokens[last - 1]).matches()
                && RECFM.matcher(tokens[last - 2]).matches()) {
            blockSize = Integer.parseInt(tokens[last]);
            recordLength = Integer.parseInt(tokens[last - 1]);
            recordFormat = tokens[last - 2];
        }
        for (String token : tokens) {
            if (DATE.matcher(token).matches()) {
                referred = token;
                break;
            }
        }
        if (organization.isEmpty() && recordFormat.isEmpty()) {
            return null;
        }
        return new Dataset(name, tokens[0], recordFormat, recordLength, blockSize, organization, referred, false);
    }
}
