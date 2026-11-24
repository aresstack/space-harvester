package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import com.aresstack.spaceharvester.deck.ftp.mvs.MvsLocation;
import com.aresstack.spaceharvester.deck.ftp.mvs.MvsLocationType;
import com.aresstack.spaceharvester.deck.ftp.mvs.MvsQuoteNormalizer;
import java.io.IOException;
import org.apache.commons.net.ftp.FTPClient;

/**
 * Ob ein Dataset partitioniert ist (PDS/PDSE) und wie ein Ordner geöffnet wird – unverändert aus MainframeMate
 * ({@code MvsFtpClient.isLikelyPartitionedDataset}): {@code NLST 'DS(*)'} liefert Members, 501 bzw. „not a
 * partitioned“ heißt sequenziell, im Zweifel partitioniert.
 */
final class MvsDatasetProbe {

    private MvsDatasetProbe() {
    }

    /**
     * Best-effort probe whether a dataset is partitioned (PDS/PDSE) and should be browsed for members.
     */
    static boolean isLikelyPartitionedDataset(FTPClient ftpClient, MvsLocation location) {
        if (location == null || location.getType() != MvsLocationType.DATASET) {
            return false;
        }
        try {
            String dataset = MvsQuoteNormalizer.unquote(location.getLogicalPath());
            String memberQuery = MvsQuoteNormalizer.normalize(dataset + "(*)");
            String[] names = ftpClient.listNames(memberQuery);
            int replyCode = ftpClient.getReplyCode();
            String reply = ftpClient.getReplyString();
            String replyUpper = reply == null ? "" : reply.toUpperCase();
            if (names != null && names.length > 0) {
                return true;
            }
            if (replyCode == 501 || replyUpper.contains("NOT A PARTITIONED") || replyUpper.contains("REQUESTS MEMBERS BUT")) {
                return false;
            }
            if (replyCode == 550 && replyUpper.contains("NO MEMBERS FOUND")) {
                return true;
            }
            return true;
        } catch (IOException e) {
            return true;
        }
    }
}
