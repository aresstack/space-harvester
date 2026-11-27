package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.AiLayoutResolverSettings;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;
import com.aresstack.askai.browser.search.layout.SearchPageSignalScore;

import java.util.List;

/**
 * Builds the system and user prompts for the AI layout resolver from the bounded artifact and the
 * configured templates. The model may ONLY choose among the mechanically identified container ids:
 * the prompt lists exactly those ids and the exact snapshot id it must echo, and forbids inventing
 * ids, urls, selectors or DOM paths. It never asks the model to judge result relevance to the query
 * or to rate target pages — that is out of scope for A4.
 */
final class SearchPageLayoutPromptFactory {

    String systemPrompt(AiLayoutResolverSettings settings) {
        return settings.systemPromptTemplate;
    }

    String userPrompt(SearchPageAnalysisArtifact artifact, AiLayoutResolverSettings settings) {
        String rendered = settings.userPromptTemplate
                .replace("{query}", artifact.searchQuery)
                .replace("{pageUrl}", artifact.pageUrl)
                .replace("{containerDescriptors}", renderCandidates(artifact));
        StringBuilder sb = new StringBuilder(rendered);
        sb.append("\n\n").append(constraints(artifact));
        sb.append('\n').append(schema(artifact));
        return sb.toString();
    }

    /** The repair suffix for a retry: the previous (bad) answer and the concrete violations. */
    String repairSuffix(SearchPageAnalysisArtifact artifact, String previousResponse,
                        List<String> violations, boolean includePreviousResponse,
                        boolean includeValidationErrors) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nYour previous answer was rejected.");
        if (includeValidationErrors && violations != null && !violations.isEmpty()) {
            sb.append("\nValidation problems:");
            for (String violation : violations) {
                sb.append("\n- ").append(violation);
            }
        }
        if (includePreviousResponse && previousResponse != null && !previousResponse.isEmpty()) {
            sb.append("\nPrevious response:\n").append(previousResponse);
        }
        sb.append("\n").append(constraints(artifact));
        sb.append("\nReturn only a corrected JSON object matching the schema.");
        return sb.toString();
    }

    private String constraints(SearchPageAnalysisArtifact artifact) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analysis id (echo it EXACTLY): ").append(artifact.analysisId);
        sb.append("\nSnapshot id (echo it EXACTLY): ").append(artifact.snapshotId);
        sb.append("\nChoose ONLY among these container ids — inventing an id is a hard failure:");
        sb.append('\n').append(allowedIds(artifact));
        sb.append("\nDo not invent ids, urls, css selectors or DOM paths. Do not rerank results by");
        sb.append(" relevance and do not judge target-page content.");
        // The two-level hierarchy contract the machine validator enforces — spelled out explicitly,
        // because "name the organic containers" alone reads as one flat list. It lives here (code,
        // not the configurable template) so even a frozen session profile gets the full contract.
        sb.append("\nField contract (your answer is machine-validated against it):");
        sb.append("\n- organicResultContainerIds: the parent REGION container(s) that contain the");
        sb.append(" organic result list. NOT individual result cards. NOT a root or full-page");
        sb.append(" container.");
        sb.append("\n- resultBlockContainerIds: the individual organic result cards. Every block");
        sb.append(" MUST be a DIRECT CHILD of one selected organic region: its parent= id shown in");
        sb.append(" the candidate list must itself appear in organicResultContainerIds.");
        sb.append("\n- excludedContainerIds: advertisements, sponsored results, navigation,");
        sb.append(" vertical modules and other unrelated containers.");
        sb.append("\nBefore answering, verify for EVERY resultBlockContainerId that its parent=");
        sb.append(" value is contained in organicResultContainerIds.");
        return sb.toString();
    }

    private String allowedIds(SearchPageAnalysisArtifact artifact) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (SearchPageContainerCandidate candidate : artifact.containerCandidates) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(candidate.containerId);
            first = false;
        }
        return sb.append(']').toString();
    }

    private String renderCandidates(SearchPageAnalysisArtifact artifact) {
        StringBuilder sb = new StringBuilder();
        for (SearchPageContainerCandidate c : artifact.containerCandidates) {
            sb.append("- id=").append(c.containerId);
            sb.append(" parent=").append(c.parentContainerId.isEmpty() ? "-" : c.parentContainerId);
            sb.append(" tag=").append(c.tagName);
            if (!c.role.isEmpty()) {
                sb.append(" role=").append(c.role);
            }
            if (!c.semanticFlags.isEmpty()) {
                sb.append(" semantics=").append(c.semanticFlags);
            }
            sb.append(" links=").append(c.linkCount);
            sb.append(" external=").append(c.externalDomainLinkCount);
            sb.append(" sameHost=").append(c.sameHostLinkCount);
            sb.append(" headings=").append(c.headingCount);
            sb.append(" similarSiblings=").append(c.similarSiblingCount);
            sb.append(" signature=").append(c.structureSignature);
            sb.append(" score=").append(round(c.totalScore));
            sb.append(" families=").append(renderScores(c.signalScores));
            if (!c.textExcerpt.isEmpty()) {
                sb.append(" text=\"").append(c.textExcerpt).append('"');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String renderScores(List<SearchPageSignalScore> scores) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (SearchPageSignalScore score : scores) {
            if (score.score == 0) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            sb.append(score.family).append('=').append(round(score.score));
            first = false;
        }
        return sb.append('}').toString();
    }

    private String schema(SearchPageAnalysisArtifact artifact) {
        return "Respond with a single JSON object of this exact shape:\n"
                + "{\n"
                + "  \"analysisId\": \"" + artifact.analysisId + "\",\n"
                + "  \"snapshotId\": \"" + artifact.snapshotId + "\",\n"
                + "  \"organicResultContainerIds\": [\"<id>\"],\n"
                + "  \"resultBlockContainerIds\": [\"<id>\"],\n"
                + "  \"excludedContainerIds\": [\"<id>\"],\n"
                + "  \"confidence\": 0.0,\n"
                + "  \"explanation\": \"<short reason, diagnostics only>\"\n"
                + "}";
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
