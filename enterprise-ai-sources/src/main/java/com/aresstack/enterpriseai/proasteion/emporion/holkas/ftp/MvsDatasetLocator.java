package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

/**
 * MVS-Hilfsklasse.
 *
 * <p>Übernommen aus corenth ({@code proasteion.emporion.holkas.mvs}).
 */
final class MvsDatasetLocator {

    private final String datasetName;

    private MvsDatasetLocator(String datasetName) {
        if (datasetName == null || datasetName.trim().isEmpty()) {
            throw new IllegalArgumentException("datasetName must not be null or empty");
        }
        this.datasetName = MvsQuoteNormalizer.unquote(MvsQuoteNormalizer.normalize(datasetName));
    }

    public static MvsDatasetLocator of(String datasetName) {
        return new MvsDatasetLocator(datasetName);
    }

    public String datasetName() {
        return datasetName;
    }

    public String quoted() {
        return MvsQuoteNormalizer.normalize(datasetName);
    }

    public String displayName() {
        int lastDot = datasetName.lastIndexOf('.');
        return lastDot >= 0 && lastDot < datasetName.length() - 1
                ? datasetName.substring(lastDot + 1)
                : datasetName;
    }

    public MvsMemberLocator member(String memberName) {
        return new MvsMemberLocator(this, memberName);
    }
}
