package com.aresstack.askai.research.runtime.acquire;

/**
 * The narrow model seam {@link ModelPageReadinessJudge} calls: a single chat completion for a system+user
 * prompt, returning the assistant's raw text (or {@code ""} on any model/transport failure — never throws, so
 * a model problem degrades to the heuristic instead of aborting the search). The productive adapter wraps the
 * host's {@code MainModelChat}; tests inject a scripted fake.
 */
public interface PageReadinessModel {

    String complete(String systemPrompt, String userPrompt);
}
