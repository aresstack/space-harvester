package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.BlockKind;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedBlock;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedDocument;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRequest;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionResult;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ResourceExtractor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Shared test helpers: a provider over a cache folder and a minimal PDF extractor instead of Tika. */
final class GitFixtures {

    private GitFixtures() {
    }

    static GitSourceProvider provider(Path cache) {
        return new GitSourceProvider(cache, detector(), registry());
    }

    static GitKnowledgeSource open(GitSourceProvider provider, Path remote, String... keyValues) {
        Map<String, String> values = new HashMap<String, String>();
        values.put("url", remote.toAbsolutePath().toString());
        values.put("refreshMinutes", "0");
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        SourceSettings settings = SourceSettings.of(values);
        return (GitKnowledgeSource) provider.open(SourceAddress.of("git", provider.locator(settings)), settings);
    }

    static ContentDetector detector() {
        return new ContentDetector() {
            @Override
            public DetectedContentType detect(String filenameHint, String contentTypeHint, byte[] contentPrefix) {
                return filenameHint != null && filenameHint.endsWith(".pdf")
                        ? new DetectedContentType("application/pdf", ContentCategory.PDF, filenameHint)
                        : new DetectedContentType("application/octet-stream", ContentCategory.BINARY, filenameHint);
            }
        };
    }

    static ExtractionRegistry registry() {
        ExtractionRegistry registry = new ExtractionRegistry();
        registry.register(new ResourceExtractor() {
            @Override
            public boolean supports(DetectedContentType contentType) {
                return contentType.category() == ContentCategory.PDF;
            }

            @Override
            public ExtractionResult extract(ExtractionRequest request) {
                ExtractedDocument document = ExtractedDocument.builder()
                        .contentType(request.detectedContentType())
                        .addBlock(new ExtractedBlock(0, BlockKind.TEXT, "PDF text of " + request.filenameHint()))
                        .build();
                return ExtractionResult.success(request.resourceId(), request.detectedContentType(), document);
            }
        });
        return registry;
    }
}
