package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

/**
 * Address of a member inside an MVS partitioned dataset.
 *
 * <p>Übernommen aus corenth ({@code proasteion.emporion.holkas.mvs}).
 */
final class MvsMemberLocator {

    private final MvsDatasetLocator dataset;
    private final String memberName;

    public MvsMemberLocator(MvsDatasetLocator dataset, String memberName) {
        if (dataset == null) {
            throw new IllegalArgumentException("dataset must not be null");
        }
        if (memberName == null || memberName.trim().isEmpty()) {
            throw new IllegalArgumentException("memberName must not be null or empty");
        }
        this.dataset = dataset;
        this.memberName = memberName.trim();
    }

    public static MvsMemberLocator parse(String memberSpec) {
        String unquoted = MvsQuoteNormalizer.unquote(memberSpec);
        int open = unquoted.indexOf('(');
        int close = unquoted.lastIndexOf(')');
        if (open <= 0 || close != unquoted.length() - 1 || open >= close) {
            throw new IllegalArgumentException("Not an MVS member spec: " + memberSpec);
        }
        return new MvsMemberLocator(MvsDatasetLocator.of(unquoted.substring(0, open)),
                unquoted.substring(open + 1, close));
    }

    public MvsDatasetLocator dataset() {
        return dataset;
    }

    public String memberName() {
        return memberName;
    }

    public String memberSpec() {
        return dataset.datasetName() + "(" + memberName + ")";
    }

    public String quoted() {
        return MvsQuoteNormalizer.normalize(memberSpec());
    }
}
