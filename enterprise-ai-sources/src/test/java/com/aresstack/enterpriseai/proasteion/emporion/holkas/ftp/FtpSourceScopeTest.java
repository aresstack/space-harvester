package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.SourceScope;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class FtpSourceScopeTest {

    @Test
    public void acceptsManuallyEnteredQualifiersAndExistingDatasetStartPoints() {
        SourceSettings settings = SourceSettings.empty().with("startPoints",
                " HLQ1; HLQ2, HLQ3.COBOL.SRC\nHLQ3.JCL(PGM1); HLQ1 ");
        SourceScope scope = provider().scope(settings);
        assertEquals(Arrays.asList("HLQ1", "HLQ2", "HLQ3.COBOL.SRC", "HLQ3.JCL(PGM1)"),
                scope.startPoints());
        assertEquals(Integer.MAX_VALUE, scope.maxDepth());
        assertEquals(Integer.MAX_VALUE, scope.maxResources());
    }

    @Test
    public void preservesExplicitScopeRestrictions() {
        SourceScope scope = provider().scope(SourceSettings.empty().with("startPoints", "/sources")
                .with("maxDepth", "2").with("maxResources", "50"));
        assertEquals(Collections.singletonList("/sources"), scope.startPoints());
        assertEquals(2, scope.maxDepth());
        assertEquals(50, scope.maxResources());
    }

    @Test
    public void requiresExplicitStartPointsForFullSynchronization() {
        SourceSettings settings = SourceSettings.empty().with("host", "localhost");
        org.junit.Assert.assertTrue(provider().validate(settings).stream()
                .anyMatch(problem -> problem.contains("HLQ")));
    }

    @Test
    public void allowsNavigationOnlyWithoutAnyInventoryStartPoint() {
        SourceSettings settings = SourceSettings.empty().with("host", "localhost").with("navigationFill", "true");
        org.junit.Assert.assertTrue(provider().validate(settings).isEmpty());
        org.junit.Assert.assertTrue(provider().scope(settings).startPoints().isEmpty());
    }

    private static FtpSourceProvider provider() {
        return new FtpSourceProvider(ref -> {
            throw new AssertionError("Do not access credentials while configuring a scope");
        });
    }
}