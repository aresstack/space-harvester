package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.LegacySearchAttemptOutcome;
import com.aresstack.askai.browser.LegacySearchEngineAttemptResult;
import com.aresstack.askai.browser.render.RenderedBox;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;
import com.aresstack.askai.browser.search.layout.EngineFamily;
import com.aresstack.askai.browser.search.layout.MechanicalConfidenceOutcome;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisAttempt;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisDiagnosticArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate;
import com.aresstack.askai.browser.search.layout.SearchPageSignalScore;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairAttemptId;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairStatus;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairSubmission;
import com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The pure Java-8 wire codec for the SERP layout repair bridge — the ONLY thing that crosses the MCP
 * boundary between the model-free sidecar and the model-using research runtime. It round-trips every
 * binding value fully (repair ticket id, analysis id, snapshot id + generation, document fingerprint,
 * layout structure fingerprint, settings digest) and HARD-REJECTS malformed payloads: missing required
 * fields, wrong types and unknown enum/status values all throw {@link DecodeException}. No decision is
 * ever reconstructed from a human {@code ATTEMPT:} line.
 */
public final class SearchLayoutRepairJson {

    /** Thrown for any structurally invalid or type-mismatched payload — never silently repaired. */
    public static final class DecodeException extends RuntimeException {
        public DecodeException(String message) {
            super(message);
        }
    }

    private SearchLayoutRepairJson() {
    }

    // ------------------------------------------------------------------ prepared result

    public static String encodePrepared(PreparedWebSearchResult prepared) {
        StringBuilder sb = new StringBuilder("{");
        str(sb, "status", prepared.status.name()).append(',');
        sb.append("\"candidates\":");
        candidates(sb, prepared.candidates).append(',');
        sb.append("\"repairRequests\":[");
        for (int i = 0; i < prepared.repairRequests.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            repairRequest(sb, prepared.repairRequests.get(i));
        }
        sb.append("],");
        sb.append("\"providerHosts\":");
        stringArray(sb, prepared.providerHosts).append(',');
        sb.append("\"engineAttempts\":[");
        for (int i = 0; i < prepared.engineAttempts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            LegacySearchEngineAttemptResult a = prepared.engineAttempts.get(i);
            sb.append('{');
            str(sb, "engineHost", a.getSearchEngineHost()).append(',');
            str(sb, "outcome", a.getOutcome().name()).append(',');
            str(sb, "diagnosticCode", a.getDiagnosticCode());
            sb.append('}');
        }
        sb.append("],");
        sb.append("\"challenges\":[");
        for (int i = 0; i < prepared.challenges.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SearchChallengeState c = prepared.challenges.get(i);
            sb.append('{');
            str(sb, "family", c.family).append(',');
            str(sb, "url", c.url);
            sb.append('}');
        }
        sb.append("],");
        sb.append("\"diagnostics\":");
        stringArray(sb, prepared.diagnostics);
        return sb.append('}').toString();
    }

    public static PreparedWebSearchResult decodePrepared(String json) {
        Map<String, Object> o = object(json);
        WebSearchPreparationStatus status = preparationStatus(reqStr(o, "status"));
        List<SearchResultCandidate> candidates = decodeCandidates(reqList(o, "candidates"));
        List<SearchLayoutRepairRequest> requests = new ArrayList<SearchLayoutRepairRequest>();
        for (Object element : reqList(o, "repairRequests")) {
            requests.add(decodeRepairRequest(asObject(element, "repairRequests[]")));
        }
        List<LegacySearchEngineAttemptResult> attempts =
                new ArrayList<LegacySearchEngineAttemptResult>();
        for (Object element : reqList(o, "engineAttempts")) {
            Map<String, Object> a = asObject(element, "engineAttempts[]");
            attempts.add(new LegacySearchEngineAttemptResult(reqStr(a, "engineHost"),
                    attemptOutcome(reqStr(a, "outcome")), reqStr(a, "diagnosticCode")));
        }
        List<SearchChallengeState> challenges = new ArrayList<SearchChallengeState>();
        for (Object element : reqList(o, "challenges")) {
            Map<String, Object> c = asObject(element, "challenges[]");
            challenges.add(new SearchChallengeState(reqStr(c, "family"), reqStr(c, "url")));
        }
        return new PreparedWebSearchResult(status, candidates, requests,
                stringList(reqList(o, "providerHosts"), "providerHosts"), attempts, challenges,
                stringList(reqList(o, "diagnostics"), "diagnostics"));
    }

    // ------------------------------------------------------------------ submission

    public static String encodeSubmission(SearchLayoutRepairSubmission submission) {
        StringBuilder sb = new StringBuilder("{");
        str(sb, "repairTicketId", submission.attemptId.value).append(',');
        str(sb, "analysisId", submission.analysisId).append(',');
        str(sb, "snapshotId", submission.snapshotId).append(',');
        num(sb, "snapshotGeneration", submission.snapshotGeneration).append(',');
        str(sb, "documentFingerprint", submission.documentFingerprint).append(',');
        str(sb, "layoutStructureFingerprint", submission.layoutStructureFingerprint).append(',');
        str(sb, "settingsDigest", submission.settingsDigest).append(',');
        sb.append("\"decision\":");
        decision(sb, submission.decision);
        return sb.append('}').toString();
    }

    public static SearchLayoutRepairSubmission decodeSubmission(String json) {
        Map<String, Object> o = object(json);
        return new SearchLayoutRepairSubmission(
                new SearchLayoutRepairAttemptId(reqStr(o, "repairTicketId")), reqStr(o, "analysisId"),
                reqStr(o, "snapshotId"), reqLong(o, "snapshotGeneration"),
                reqStr(o, "documentFingerprint"), reqStr(o, "layoutStructureFingerprint"),
                reqStr(o, "settingsDigest"), decodeDecision(reqObject(o, "decision")));
    }

    // ------------------------------------------------------------------ apply result

    public static String encodeRepairResult(SearchLayoutRepairResult result) {
        StringBuilder sb = new StringBuilder("{");
        str(sb, "status", result.status.name()).append(',');
        sb.append("\"candidates\":");
        candidates(sb, result.candidates).append(',');
        sb.append("\"diagnostics\":");
        stringArray(sb, result.diagnostics);
        return sb.append('}').toString();
    }

    public static SearchLayoutRepairResult decodeRepairResult(String json) {
        Map<String, Object> o = object(json);
        return new SearchLayoutRepairResult(repairStatus(reqStr(o, "status")),
                decodeCandidates(reqList(o, "candidates")),
                stringList(reqList(o, "diagnostics"), "diagnostics"));
    }

    // ------------------------------------------------------------------ repair request

    private static void repairRequest(StringBuilder sb, SearchLayoutRepairRequest r) {
        sb.append('{');
        str(sb, "repairTicketId", r.attemptId.value).append(',');
        str(sb, "analysisId", r.artifact == null ? "" : r.artifact.analysisId).append(',');
        str(sb, "query", r.query).append(',');
        str(sb, "engineHost", r.engineHost).append(',');
        str(sb, "engineFamily", r.engineFamily.name()).append(',');
        str(sb, "snapshotId", r.snapshotId).append(',');
        num(sb, "snapshotGeneration", r.snapshotGeneration).append(',');
        str(sb, "documentFingerprint", r.documentFingerprint).append(',');
        str(sb, "layoutStructureFingerprint", r.layoutStructureFingerprint).append(',');
        num(sb, "createdAtEpochMillis", r.createdAtEpochMillis).append(',');
        num(sb, "expiresAtEpochMillis", r.expiresAtEpochMillis).append(',');
        sb.append("\"artifact\":");
        artifact(sb, r.artifact).append(',');
        sb.append("\"diagnostics\":");
        diagnostic(sb, r.diagnostics);
        sb.append('}');
    }

    private static SearchLayoutRepairRequest decodeRepairRequest(Map<String, Object> o) {
        return new SearchLayoutRepairRequest(
                new SearchLayoutRepairAttemptId(reqStr(o, "repairTicketId")), reqStr(o, "query"),
                reqStr(o, "engineHost"), engineFamily(reqStr(o, "engineFamily")),
                reqStr(o, "snapshotId"), reqLong(o, "snapshotGeneration"),
                reqStr(o, "documentFingerprint"), reqStr(o, "layoutStructureFingerprint"),
                decodeArtifact(reqObject(o, "artifact")),
                decodeDiagnostic(reqObject(o, "diagnostics")),
                reqLong(o, "createdAtEpochMillis"), reqLong(o, "expiresAtEpochMillis"));
    }

    // ------------------------------------------------------------------ artifact

    private static StringBuilder artifact(StringBuilder sb, SearchPageAnalysisArtifact a) {
        sb.append('{');
        str(sb, "analysisId", a.analysisId).append(',');
        str(sb, "snapshotId", a.snapshotId).append(',');
        num(sb, "snapshotGeneration", a.snapshotGeneration).append(',');
        str(sb, "documentFingerprint", a.documentFingerprint).append(',');
        str(sb, "searchQuery", a.searchQuery).append(',');
        str(sb, "engineFamily", a.engineFamily.name()).append(',');
        str(sb, "pageUrl", a.pageUrl).append(',');
        str(sb, "pageTitle", a.pageTitle).append(',');
        str(sb, "mechanicalOutcome", a.mechanicalOutcome.name()).append(',');
        dbl(sb, "mechanicalConfidence", a.mechanicalConfidence).append(',');
        sb.append("\"mechanicallyPreferredContainerIds\":");
        stringArray(sb, a.mechanicallyPreferredContainerIds).append(',');
        sb.append("\"containerCandidates\":[");
        for (int i = 0; i < a.containerCandidates.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            candidate(sb, a.containerCandidates.get(i));
        }
        sb.append("],");
        sb.append("\"mechanicalRejectionReasons\":");
        stringArray(sb, a.mechanicalRejectionReasons).append(',');
        sb.append("\"captureWarnings\":");
        stringArray(sb, a.captureWarnings).append(',');
        str(sb, "settingsDigest", a.settingsDigest);
        return sb.append('}');
    }

    private static SearchPageAnalysisArtifact decodeArtifact(Map<String, Object> o) {
        List<SearchPageContainerCandidate> candidates =
                new ArrayList<SearchPageContainerCandidate>();
        for (Object element : reqList(o, "containerCandidates")) {
            candidates.add(decodeCandidate(asObject(element, "containerCandidates[]")));
        }
        return new SearchPageAnalysisArtifact(reqStr(o, "analysisId"), reqStr(o, "snapshotId"),
                reqLong(o, "snapshotGeneration"), reqStr(o, "documentFingerprint"),
                reqStr(o, "searchQuery"), engineFamily(reqStr(o, "engineFamily")),
                reqStr(o, "pageUrl"), reqStr(o, "pageTitle"),
                mechanicalOutcome(reqStr(o, "mechanicalOutcome")),
                reqDouble(o, "mechanicalConfidence"),
                stringList(reqList(o, "mechanicallyPreferredContainerIds"), "preferred"), candidates,
                stringList(reqList(o, "mechanicalRejectionReasons"), "rejections"),
                stringList(reqList(o, "captureWarnings"), "warnings"), reqStr(o, "settingsDigest"));
    }

    // ------------------------------------------------------------------ candidate

    private static void candidate(StringBuilder sb, SearchPageContainerCandidate c) {
        sb.append('{');
        str(sb, "containerId", c.containerId).append(',');
        str(sb, "parentContainerId", c.parentContainerId).append(',');
        str(sb, "tagName", c.tagName).append(',');
        str(sb, "role", c.role).append(',');
        sb.append("\"semanticFlags\":");
        stringArray(sb, c.semanticFlags).append(',');
        str(sb, "textExcerpt", c.textExcerpt).append(',');
        num(sb, "totalTextLength", c.totalTextLength).append(',');
        num(sb, "nonLinkTextLength", c.nonLinkTextLength).append(',');
        num(sb, "headingCount", c.headingCount).append(',');
        num(sb, "linkCount", c.linkCount).append(',');
        num(sb, "sameHostLinkCount", c.sameHostLinkCount).append(',');
        num(sb, "sameRegistrableDomainLinkCount", c.sameRegistrableDomainLinkCount).append(',');
        num(sb, "externalDomainLinkCount", c.externalDomainLinkCount).append(',');
        dbl(sb, "boxX", c.boundingBox.x).append(',');
        dbl(sb, "boxY", c.boundingBox.y).append(',');
        dbl(sb, "boxW", c.boundingBox.width).append(',');
        dbl(sb, "boxH", c.boundingBox.height).append(',');
        dbl(sb, "viewportIntersectionRatio", c.viewportIntersectionRatio).append(',');
        bool(sb, "containsViewportCenter", c.containsViewportCenter).append(',');
        dbl(sb, "horizontalCenterDistance", c.horizontalCenterDistance).append(',');
        dbl(sb, "verticalCenterDistance", c.verticalCenterDistance).append(',');
        dbl(sb, "backgroundDistanceToParent", c.backgroundDistanceToParent).append(',');
        dbl(sb, "backgroundDistanceToPage", c.backgroundDistanceToPage).append(',');
        str(sb, "borderSummary", c.borderSummary).append(',');
        bool(sb, "hasBoxShadow", c.hasBoxShadow).append(',');
        str(sb, "structureSignature", c.structureSignature).append(',');
        num(sb, "similarSiblingCount", c.similarSiblingCount).append(',');
        str(sb, "ancestrySignature", c.ancestrySignature).append(',');
        sb.append("\"signalScores\":[");
        for (int i = 0; i < c.signalScores.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SearchPageSignalScore s = c.signalScores.get(i);
            sb.append('{');
            str(sb, "family", s.family).append(',');
            dbl(sb, "score", s.score);
            sb.append('}');
        }
        sb.append("],");
        dbl(sb, "totalScore", c.totalScore).append(',');
        str(sb, "rejectionReason", c.rejectionReason);
        sb.append('}');
    }

    private static SearchPageContainerCandidate decodeCandidate(Map<String, Object> o) {
        List<SearchPageSignalScore> scores = new ArrayList<SearchPageSignalScore>();
        for (Object element : reqList(o, "signalScores")) {
            Map<String, Object> s = asObject(element, "signalScores[]");
            scores.add(new SearchPageSignalScore(reqStr(s, "family"), reqDouble(s, "score")));
        }
        RenderedBox box = new RenderedBox(reqDouble(o, "boxX"), reqDouble(o, "boxY"),
                reqDouble(o, "boxW"), reqDouble(o, "boxH"));
        return new SearchPageContainerCandidate(reqStr(o, "containerId"),
                reqStr(o, "parentContainerId"), reqStr(o, "tagName"), reqStr(o, "role"),
                stringList(reqList(o, "semanticFlags"), "semanticFlags"), reqStr(o, "textExcerpt"),
                reqInt(o, "totalTextLength"), reqInt(o, "nonLinkTextLength"),
                reqInt(o, "headingCount"), reqInt(o, "linkCount"), reqInt(o, "sameHostLinkCount"),
                reqInt(o, "sameRegistrableDomainLinkCount"), reqInt(o, "externalDomainLinkCount"),
                box, reqDouble(o, "viewportIntersectionRatio"),
                reqBool(o, "containsViewportCenter"), reqDouble(o, "horizontalCenterDistance"),
                reqDouble(o, "verticalCenterDistance"), reqDouble(o, "backgroundDistanceToParent"),
                reqDouble(o, "backgroundDistanceToPage"), reqStr(o, "borderSummary"),
                reqBool(o, "hasBoxShadow"), reqStr(o, "structureSignature"),
                reqInt(o, "similarSiblingCount"), reqStr(o, "ancestrySignature"), scores,
                reqDouble(o, "totalScore"), reqStr(o, "rejectionReason"));
    }

    // ------------------------------------------------------------------ decision

    private static void decision(StringBuilder sb, ValidatedSearchPageLayoutDecision d) {
        sb.append('{');
        str(sb, "analysisId", d.analysisId).append(',');
        str(sb, "snapshotId", d.snapshotId).append(',');
        num(sb, "snapshotGeneration", d.snapshotGeneration).append(',');
        str(sb, "documentFingerprint", d.documentFingerprint).append(',');
        str(sb, "settingsDigest", d.settingsDigest).append(',');
        str(sb, "primaryOrganicContainerId", d.primaryOrganicContainerId).append(',');
        sb.append("\"organicResultContainerIds\":");
        stringArray(sb, d.organicResultContainerIds).append(',');
        sb.append("\"resultBlockContainerIds\":");
        stringArray(sb, d.resultBlockContainerIds).append(',');
        sb.append("\"excludedContainerIds\":");
        stringArray(sb, d.excludedContainerIds).append(',');
        dbl(sb, "confidence", d.confidence);
        sb.append('}');
    }

    private static ValidatedSearchPageLayoutDecision decodeDecision(Map<String, Object> o) {
        return new ValidatedSearchPageLayoutDecision(reqStr(o, "analysisId"), reqStr(o, "snapshotId"),
                reqLong(o, "snapshotGeneration"), reqStr(o, "documentFingerprint"),
                reqStr(o, "settingsDigest"), reqStr(o, "primaryOrganicContainerId"),
                stringList(reqList(o, "organicResultContainerIds"), "organic"),
                stringList(reqList(o, "resultBlockContainerIds"), "blocks"),
                stringList(reqList(o, "excludedContainerIds"), "excluded"),
                reqDouble(o, "confidence"));
    }

    // ------------------------------------------------------------------ diagnostic artifact (compact)

    private static StringBuilder diagnostic(StringBuilder sb, SearchPageAnalysisDiagnosticArtifact d) {
        if (d == null) {
            return sb.append("null");
        }
        sb.append('{');
        str(sb, "analysisId", d.analysisId).append(',');
        str(sb, "snapshotId", d.snapshotId).append(',');
        str(sb, "engineFamily", d.engineFamily.name()).append(',');
        str(sb, "mechanicalOutcome", d.mechanicalOutcome.name()).append(',');
        dbl(sb, "mechanicalConfidence", d.mechanicalConfidence).append(',');
        sb.append("\"mechanicallyPreferredContainerIds\":");
        stringArray(sb, d.mechanicallyPreferredContainerIds).append(',');
        sb.append("\"rejectedContainers\":");
        stringArray(sb, d.rejectedContainers).append(',');
        sb.append("\"validationFailures\":");
        stringArray(sb, d.validationFailures).append(',');
        num(sb, "repairRetries", d.repairRetries).append(',');
        bool(sb, "rawModelResponsesIncluded", d.rawModelResponsesIncluded).append(',');
        str(sb, "profileOutcome", d.profileOutcome).append(',');
        str(sb, "finalOutcome", d.finalOutcome).append(',');
        bool(sb, "truncated", d.truncated).append(',');
        sb.append("\"aiAttempts\":[");
        for (int i = 0; i < d.aiAttempts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            attempt(sb, d.aiAttempts.get(i));
        }
        sb.append(']');
        return sb.append('}');
    }

    private static SearchPageAnalysisDiagnosticArtifact decodeDiagnostic(Map<String, Object> o) {
        List<SearchPageAnalysisAttempt> attempts = new ArrayList<SearchPageAnalysisAttempt>();
        for (Object element : reqList(o, "aiAttempts")) {
            attempts.add(decodeAttempt(asObject(element, "aiAttempts[]")));
        }
        return new SearchPageAnalysisDiagnosticArtifact(reqStr(o, "analysisId"),
                reqStr(o, "snapshotId"), engineFamily(reqStr(o, "engineFamily")),
                mechanicalOutcome(reqStr(o, "mechanicalOutcome")),
                reqDouble(o, "mechanicalConfidence"),
                stringList(reqList(o, "mechanicallyPreferredContainerIds"), "preferred"),
                Collections.<SearchPageContainerCandidate>emptyList(),
                stringList(reqList(o, "rejectedContainers"), "rejected"), attempts,
                stringList(reqList(o, "validationFailures"), "validationFailures"),
                reqInt(o, "repairRetries"), reqBool(o, "rawModelResponsesIncluded"),
                reqStr(o, "profileOutcome"), reqStr(o, "finalOutcome"), reqBool(o, "truncated"));
    }

    private static void attempt(StringBuilder sb, SearchPageAnalysisAttempt a) {
        sb.append('{');
        num(sb, "attemptNumber", a.attemptNumber).append(',');
        str(sb, "inferenceStatus", a.inferenceStatus.name()).append(',');
        bool(sb, "parsed", a.parsed).append(',');
        bool(sb, "accepted", a.accepted).append(',');
        sb.append("\"violations\":");
        stringArray(sb, a.violations).append(',');
        str(sb, "rawResponse", a.rawResponse);
        sb.append('}');
    }

    private static SearchPageAnalysisAttempt decodeAttempt(Map<String, Object> o) {
        return new SearchPageAnalysisAttempt(reqInt(o, "attemptNumber"),
                inferenceStatus(reqStr(o, "inferenceStatus")), reqBool(o, "parsed"),
                reqBool(o, "accepted"), stringList(reqList(o, "violations"), "violations"),
                reqStr(o, "rawResponse"));
    }

    // ------------------------------------------------------------------ candidates

    private static StringBuilder candidates(StringBuilder sb, List<SearchResultCandidate> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SearchResultCandidate c = list.get(i);
            sb.append('{');
            str(sb, "candidateId", c.candidateId).append(',');
            str(sb, "snapshotId", c.snapshotId).append(',');
            str(sb, "resolvedTargetUrl", c.resolvedTargetUrl).append(',');
            str(sb, "rawSearchHref", c.rawSearchHref).append(',');
            str(sb, "title", c.title).append(',');
            str(sb, "snippet", c.snippet).append(',');
            str(sb, "displayedDomain", c.displayedDomain).append(',');
            num(sb, "originalRank", c.originalRank).append(',');
            str(sb, "resultContainerId", c.resultContainerId).append(',');
            str(sb, "resultBlockContainerId", c.resultBlockContainerId).append(',');
            dbl(sb, "structuralConfidence", c.structuralConfidence).append(',');
            dbl(sb, "primaryLinkConfidence", c.primaryLinkConfidence).append(',');
            str(sb, "engineHost", c.engineHost).append(',');
            str(sb, "urlResolution", c.urlResolution.name()).append(',');
            str(sb, "originalUrl", c.originalUrl).append(',');
            sb.append("\"siteLinks\":[");
            for (int j = 0; j < c.siteLinks.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                SearchResultSiteLink link = c.siteLinks.get(j);
                sb.append('{');
                str(sb, "url", link.url).append(',');
                str(sb, "text", link.text);
                sb.append('}');
            }
            sb.append("]}");
        }
        return sb.append(']');
    }

    private static List<SearchResultCandidate> decodeCandidates(List<Object> list) {
        List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
        for (Object element : list) {
            Map<String, Object> c = asObject(element, "candidates[]");
            List<SearchResultSiteLink> siteLinks = new ArrayList<SearchResultSiteLink>();
            for (Object linkElement : reqList(c, "siteLinks")) {
                Map<String, Object> l = asObject(linkElement, "siteLinks[]");
                siteLinks.add(new SearchResultSiteLink(reqStr(l, "url"), reqStr(l, "text")));
            }
            candidates.add(new SearchResultCandidate(reqStr(c, "candidateId"),
                    reqStr(c, "snapshotId"), reqStr(c, "resolvedTargetUrl"),
                    reqStr(c, "rawSearchHref"), reqStr(c, "title"), reqStr(c, "snippet"),
                    reqStr(c, "displayedDomain"), reqInt(c, "originalRank"),
                    reqStr(c, "resultContainerId"), reqStr(c, "resultBlockContainerId"),
                    reqDouble(c, "structuralConfidence"), reqDouble(c, "primaryLinkConfidence"),
                    siteLinks, optStr(c, "engineHost"), urlResolutionOf(optStr(c, "urlResolution")),
                    optStr(c, "originalUrl")));
        }
        return candidates;
    }

    // ------------------------------------------------------------------ enum parsing (hard reject)

    private static WebSearchPreparationStatus preparationStatus(String value) {
        try {
            return WebSearchPreparationStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new DecodeException("unknown preparation status '" + value + "'");
        }
    }

    private static SearchLayoutRepairStatus repairStatus(String value) {
        try {
            return SearchLayoutRepairStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new DecodeException("unknown repair status '" + value + "'");
        }
    }

    private static EngineFamily engineFamily(String value) {
        try {
            return EngineFamily.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new DecodeException("unknown engine family '" + value + "'");
        }
    }

    private static MechanicalConfidenceOutcome mechanicalOutcome(String value) {
        try {
            return MechanicalConfidenceOutcome.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new DecodeException("unknown mechanical outcome '" + value + "'");
        }
    }

    private static StructuredInferenceStatus inferenceStatus(String value) {
        try {
            return StructuredInferenceStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new DecodeException("unknown inference status '" + value + "'");
        }
    }

    private static LegacySearchAttemptOutcome attemptOutcome(String value) {
        try {
            return LegacySearchAttemptOutcome.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new DecodeException("unknown engine attempt outcome '" + value + "'");
        }
    }

    // ------------------------------------------------------------------ typed getters (hard reject)

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String json) {
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (MiniJson.JsonParseException e) {
            throw new DecodeException("invalid JSON: " + e.getMessage());
        }
        if (!(root instanceof Map)) {
            throw new DecodeException("payload root is not a JSON object");
        }
        return (Map<String, Object>) root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String where) {
        if (!(value instanceof Map)) {
            throw new DecodeException(where + " is not a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> reqObject(Map<String, Object> o, String key) {
        if (!o.containsKey(key)) {
            throw new DecodeException("missing required object field '" + key + "'");
        }
        return asObject(o.get(key), key);
    }

    /** askai #41; a document without the field (older writer) is DIRECT, as before. */
    private static com.aresstack.askai.browser.search.UrlResolutionStatus urlResolutionOf(String value) {
        if (value == null || value.isEmpty()) {
            return com.aresstack.askai.browser.search.UrlResolutionStatus.DIRECT;
        }
        try {
            return com.aresstack.askai.browser.search.UrlResolutionStatus.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new DecodeException("urlResolution: unknown value " + value);
        }
    }

    /** An optional string field: absent means empty, never a decode failure (older payloads). */
    private static String optStr(Map<String, Object> o, String key) {
        Object value = o.get(key);
        return value instanceof String ? (String) value : "";
    }

    private static String reqStr(Map<String, Object> o, String key) {
        Object value = o.get(key);
        if (!(value instanceof String)) {
            throw new DecodeException("field '" + key + "' must be a string");
        }
        return (String) value;
    }

    private static double reqDouble(Map<String, Object> o, String key) {
        Object value = o.get(key);
        if (!(value instanceof Double)) {
            throw new DecodeException("field '" + key + "' must be a number");
        }
        return (Double) value;
    }

    private static long reqLong(Map<String, Object> o, String key) {
        return (long) reqDouble(o, key);
    }

    private static int reqInt(Map<String, Object> o, String key) {
        return (int) reqDouble(o, key);
    }

    private static boolean reqBool(Map<String, Object> o, String key) {
        Object value = o.get(key);
        if (!(value instanceof Boolean)) {
            throw new DecodeException("field '" + key + "' must be a boolean");
        }
        return (Boolean) value;
    }

    private static List<Object> reqList(Map<String, Object> o, String key) {
        Object value = o.get(key);
        if (!(value instanceof List)) {
            throw new DecodeException("field '" + key + "' must be an array");
        }
        return (List<Object>) value;
    }

    private static List<String> stringList(List<Object> list, String where) {
        List<String> result = new ArrayList<String>(list.size());
        for (Object element : list) {
            if (!(element instanceof String)) {
                throw new DecodeException(where + " must contain only strings");
            }
            result.add((String) element);
        }
        return result;
    }

    // ------------------------------------------------------------------ writer primitives

    private static StringBuilder str(StringBuilder sb, String key, String value) {
        return sb.append('"').append(key).append("\":\"").append(escape(value)).append('"');
    }

    private static StringBuilder num(StringBuilder sb, String key, long value) {
        return sb.append('"').append(key).append("\":").append(value);
    }

    private static StringBuilder dbl(StringBuilder sb, String key, double value) {
        return sb.append('"').append(key).append("\":").append(finite(value));
    }

    private static StringBuilder bool(StringBuilder sb, String key, boolean value) {
        return sb.append('"').append(key).append("\":").append(value);
    }

    private static StringBuilder stringArray(StringBuilder sb, List<String> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(values.get(i))).append('"');
        }
        return sb.append(']');
    }

    private static String finite(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        return Double.toString(value);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
