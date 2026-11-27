package com.aresstack.askai.browser.search;

/**
 * The complete typed configuration contract of the legacy browser search (A2). Immutable; the ONLY
 * source of default values is {@link LegacyBrowserSearchDefaults} — no component may invent its own
 * substitute values. Hard invariants are NOT configurable and therefore have no field anywhere:
 * <ul>
 * <li>never all SERP anchors as a fallback result,</li>
 * <li>CAPTCHA is never solved automatically and never auto-retried on the same domain family,</li>
 * <li>no unchecked AI container ids, no unbounded AI retries,</li>
 * <li>no silent engine or settings fallbacks.</li>
 * </ul>
 */
public final class LegacyBrowserSearchSettings {

    public final LegacySearchNavigationSettings navigation;
    public final ConsentHandlingSettings consent;
    public final CaptchaHandlingSettings captcha;
    public final SearchPageReadinessSettings readiness;
    public final SearchPageAnalysisSettings analysis;
    public final SearchPageVisualAnalysisSettings visualAnalysis;
    public final SearchResultExtractionSettings extraction;
    public final AiLayoutResolverSettings aiLayoutResolver;
    public final SearchResultRerankerSettings reranker;
    public final SearchDiagnosticsSettings diagnostics;
    public final SearchLayoutRepairSettings layoutRepair;

    public LegacyBrowserSearchSettings(LegacySearchNavigationSettings navigation,
                                       ConsentHandlingSettings consent, CaptchaHandlingSettings captcha,
                                       SearchPageReadinessSettings readiness,
                                       SearchPageAnalysisSettings analysis,
                                       SearchPageVisualAnalysisSettings visualAnalysis,
                                       SearchResultExtractionSettings extraction,
                                       AiLayoutResolverSettings aiLayoutResolver,
                                       SearchResultRerankerSettings reranker,
                                       SearchDiagnosticsSettings diagnostics,
                                       SearchLayoutRepairSettings layoutRepair) {
        this.navigation = navigation;
        this.consent = consent;
        this.captcha = captcha;
        this.readiness = readiness;
        this.analysis = analysis;
        this.visualAnalysis = visualAnalysis;
        this.extraction = extraction;
        this.aiLayoutResolver = aiLayoutResolver;
        this.reranker = reranker;
        this.diagnostics = diagnostics;
        this.layoutRepair = layoutRepair;
    }
}
