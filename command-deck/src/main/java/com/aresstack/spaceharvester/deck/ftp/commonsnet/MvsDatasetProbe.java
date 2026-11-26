package com.aresstack.spaceharvester.deck.ftp.commonsnet;

import com.aresstack.spaceharvester.deck.ftp.mvs.MvsLocation;
import com.aresstack.spaceharvester.deck.ftp.mvs.MvsLocationType;
import com.aresstack.spaceharvester.deck.ftp.mvs.MvsQuoteNormalizer;
import com.aresstack.spaceharvester.deck.ftp.FtpReplyException;
import java.io.IOException;
import java.util.Locale;
import org.apache.commons.net.ftp.FTPClient;

/**
 * Ob ein Dataset partitioniert ist (PDS/PDSE) und wie ein Ordner geöffnet wird – unverändert aus MainframeMate
 * ({@code MvsFtpClient.isLikelyPartitionedDataset}): {@code NLST 'DS(*)'} liefert Members, 501 bzw. „not a
 * partitioned“ heißt sequenziell; unbekannte Antworten bleiben unklassifiziert.
 */
final class MvsDatasetProbe {

    private MvsDatasetProbe() {
    }

    /**
     * Best-effort probe whether a dataset is partitioned (PDS/PDSE) and should be browsed for members.
     */
    static boolean isLikelyPartitionedDataset(FTPClient ftpClient, MvsLocation location) throws IOException {
        if (location == null || location.getType() != MvsLocationType.DATASET) {
            return false;
        }
        String dataset = MvsQuoteNormalizer.unquote(location.getLogicalPath());
        String memberQuery = MvsQuoteNormalizer.normalize(dataset + "(*)");
        String[] names = ftpClient.listNames(memberQuery);
        int replyCode = ftpClient.getReplyCode();
        String reply = ftpClient.getReplyString();
        String replyUpper = reply == null ? "" : reply.toUpperCase(Locale.ROOT);
        if (names != null && org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(replyCode)) {
            return true;
        }
        if (replyUpper.contains("NOT A PARTITIONED") || replyUpper.contains("REQUESTS MEMBERS BUT")) {
            return false;
        }
        if (replyCode == 550 && replyUpper.contains("NO MEMBERS FOUND")) {
            return true;
        }
        throw new FtpReplyException("Dataset-Typ nicht sicher ermittelbar – " + Clients.reply(ftpClient), replyCode);
    }
}
