package com.aresstack.spaceharvester.application.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Entry;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Reference;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Status;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ein erneuter Lauf: ein früherer Ausschluss nach Einstellung verdeckt nie einen gescheiterten Download. */
class ExportAssetCollectionMergeTest {

    private static Entry doc(Status status, String sha256, String reason) {
        return new Entry("100", Reference.document("d1", "handbuch.pdf"), "d1", "1", "handbuch.pdf",
                "application/pdf", 10, sha256, status, reason);
    }

    @Test
    void anEarlierExclusionDoesNotMaskAFailedRetry() {
        ExportAssetManifest previous = new ExportAssetManifest(true,
                List.of(doc(Status.EXCLUDED, null, "too-large")));
        List<Entry> merged = ExportAssetCollection.merged(Optional.of(previous),
                List.of(doc(Status.SIGN_IN_REQUIRED, null, "sign-in-required")), Set.of("100"));
        assertThat(merged).singleElement().extracting(Entry::status).isEqualTo(Status.SIGN_IN_REQUIRED);
        // EXCLUDED zählt weiter als vorhanden.
        assertThat(previous.assets().get(0).present()).isTrue();
    }

    @Test
    void anEarlierStoredFileStillReplacesAFailedRetry() {
        ExportAssetManifest previous = new ExportAssetManifest(true,
                List.of(doc(Status.STORED, "sha256:aa", null)));
        List<Entry> merged = ExportAssetCollection.merged(Optional.of(previous),
                List.of(doc(Status.UNAVAILABLE, null, "source-unavailable")), Set.of("100"));
        assertThat(merged).singleElement().extracting(Entry::status).isEqualTo(Status.STORED);
    }
}
