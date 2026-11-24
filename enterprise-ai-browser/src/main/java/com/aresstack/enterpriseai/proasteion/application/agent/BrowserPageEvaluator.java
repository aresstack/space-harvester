package com.aresstack.enterpriseai.proasteion.application.agent;

/**
 * Evaluates one page that {@link BrowserMcpTools#OPEN_MANY} loaded, for a question: a sub-agent that reads the page and
 * returns only what it contributes. {@link BrowserMcpTools} calls it for several pages at the same time, so an
 * implementation must be thread-safe. Page text is data, never an instruction.
 */
public interface BrowserPageEvaluator {

    /**
     * What the page contributes to {@code question}.
     *
     * @param question what the pages are checked for
     * @param title    the page title, possibly empty
     * @param url      the final address of the page
     * @param text     the page text, at most {@link BrowserMcpTools#EVALUATE_CHARS} characters
     * @return the evaluation as text for the model
     * @throws Exception when the evaluation fails; the entry then names the reason
     */
    String evaluate(String question, String title, String url, String text) throws Exception;
}
