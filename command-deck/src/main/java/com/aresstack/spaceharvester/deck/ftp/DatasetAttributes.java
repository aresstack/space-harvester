package com.aresstack.spaceharvester.deck.ftp;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Anlageparameter eines neuen Datasets wie im Dialog „Neues Mainframe-Dataset anlegen“ von MainframeMate. Anders als
 * dort werden sie dem Host vor dem Anlegen wirklich mitgegeben ({@code SITE RECFM=… LRECL=… BLKSIZE=… TRACKS
 * PRIMARY=… SECONDARY=… DIRECTORY=…}); ein PO-Dataset entsteht mit {@code MKD}, ein PS-Dataset mit leerem {@code STOR}.
 *
 * @param organization     PO (partitioniert, hat Members) oder PS (sequenziell)
 * @param recordFormat     FB, VB, F, V, FBA, VBA oder U
 * @param recordLength     LRECL 1–32760
 * @param blockSize        BLKSIZE 0–32760, 0 = vom System bestimmt
 * @param primaryTracks    Primärzuordnung in Spuren
 * @param secondaryTracks  Sekundärzuordnung in Spuren
 * @param directoryBlocks  Directory-Blöcke, nur für PO
 */
public record DatasetAttributes(Organization organization, String recordFormat, int recordLength, int blockSize,
                                int primaryTracks, int secondaryTracks, int directoryBlocks) {

    /** Dataset-Organisation. */
    public enum Organization {
        PO("PO – partitioniert (Members)"), PS("PS – sequenziell");

        private final String label;

        Organization(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final Set<String> RECORD_FORMATS = Set.of("FB", "VB", "F", "V", "FBA", "VBA", "U");

    /** Die Vorgaben aus MainframeMate: PO, FB, 80, 27920, 10/5 Spuren, 10 Directory-Blöcke. */
    public static final DatasetAttributes DEFAULT = new DatasetAttributes(Organization.PO, "FB", 80, 27920, 10, 5, 10);

    public DatasetAttributes {
        organization = Objects.requireNonNullElse(organization, Organization.PO);
        recordFormat = recordFormat == null ? "FB" : recordFormat.trim().toUpperCase(Locale.ROOT);
        if (!RECORD_FORMATS.contains(recordFormat)) {
            throw new IllegalArgumentException("Unbekanntes Satzformat " + recordFormat + " (FB, VB, F, V, FBA, VBA, U)");
        }
        if (recordLength < 1 || recordLength > 32760) {
            throw new IllegalArgumentException("LRECL muss zwischen 1 und 32760 liegen, nicht " + recordLength);
        }
        if (blockSize < 0 || blockSize > 32760) {
            throw new IllegalArgumentException("BLKSIZE muss zwischen 0 und 32760 liegen, nicht " + blockSize);
        }
        if (primaryTracks < 1 || secondaryTracks < 0) {
            throw new IllegalArgumentException("Primär mindestens 1 Spur, Sekundär nicht negativ");
        }
        if (organization == Organization.PO && directoryBlocks < 1) {
            throw new IllegalArgumentException("Ein PO-Dataset braucht mindestens einen Directory-Block");
        }
    }

    /** Die SITE-Parameter für den Host. */
    public String siteCommand() {
        StringBuilder site = new StringBuilder("RECFM=").append(recordFormat).append(" LRECL=").append(recordLength)
                .append(" BLKSIZE=").append(blockSize).append(" TRACKS PRIMARY=").append(primaryTracks)
                .append(" SECONDARY=").append(secondaryTracks);
        site.append(organization == Organization.PO ? " DIRECTORY=" + directoryBlocks : " DIRECTORY=0");
        return site.toString();
    }

    /** Für die Bestätigung: „PO · FB · LRECL 80 · BLKSIZE 27920 · 10/5 Spuren · 10 Directory-Blöcke“. */
    public String summary() {
        return organization.name() + " · " + recordFormat + " · LRECL " + recordLength + " · BLKSIZE " + blockSize
                + " · " + primaryTracks + "/" + secondaryTracks + " Spuren"
                + (organization == Organization.PO ? " · " + directoryBlocks + " Directory-Blöcke" : "");
    }
}
