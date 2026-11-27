package com.aresstack.enterpriseai.proasteion.application.browser;

import com.aresstack.enterpriseai.astu.approval.ApprovalPolicy;
import com.aresstack.enterpriseai.astu.approval.ApprovalTarget;
import com.aresstack.enterpriseai.astu.approval.ApprovalTargetResolver;
import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserControl;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides whether a {@code browser_act} call may change data on a site (submit a form, press Speichern/Senden/Löschen)
 * and describes it for the shared tool approval gate (#145). Reading, navigating, typing into fields, choosing options
 * and the site search stay free ({@value #OPERATE}); a data-changing action is {@value #CHANGE}, {@code ASK} by
 * default. The decision comes from the control list the runtime itself built, never from model arguments: the tool
 * hands the gate a fresh argument map built by {@link #request}, so the keys a model may send are overwritten.
 */
public final class BrowserActApproval {

    /** Approval action of a data-changing click or submit. */
    public static final String CHANGE = "change";
    /** Approval action of every other browser action. */
    public static final String OPERATE = "operate";

    static final String ARG_ACTION = "approval.action";
    static final String ARG_HOST = "approval.host";
    static final String ARG_TARGET = "approval.target";
    static final String ARG_DESCRIPTION = "approval.description";

    /** Labels of buttons that save, send, delete or book something (German and English). */
    private static final Pattern CHANGING_LABEL = Pattern.compile(".*\\b(speicher|sende|send|absend|abschick|"
            + "lösch|loesch|entfern|anleg|erfass|buch|bestätig|bestaetig|übernehm|uebernehm|einreich|bezahl|zahl|"
            + "kauf|bestell|registrier|hochlad|änder|aender|aktualisier|freigeb|genehmig|unterschreib|signier|"
            + "save|submit|delete|remove|create|confirm|apply|book|pay|order|buy|purchase|upload|update|publish|"
            + "approve|register)\\w*.*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    /** Labels of search buttons: sending a search changes nothing. */
    private static final Pattern SEARCH_LABEL = Pattern.compile(".*\\b(such|search|find|filter)\\w*.*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private BrowserActApproval() {
    }

    /** Built-in sub-rules of {@code browser_act}: {@value #CHANGE} {@code ASK}, {@value #OPERATE} {@code ALLOW}. */
    public static Map<String, ApprovalPolicy> defaultPolicies() {
        Map<String, ApprovalPolicy> policies = new LinkedHashMap<String, ApprovalPolicy>();
        policies.put(CHANGE, ApprovalPolicy.ASK);
        policies.put(OPERATE, ApprovalPolicy.ALLOW);
        return policies;
    }

    /**
     * Whether {@code action} on {@code control} may change data. {@code control} {@code null} (not in the last list
     * the runtime built) counts as changing for click and submit: unknown means ask.
     */
    public static boolean changesData(BrowserControl control, BrowserAction action) {
        if (action == null || action == BrowserAction.FILL || action == BrowserAction.SELECT) {
            return false;
        }
        if (control == null) {
            return true;
        }
        String kind = control.kind();
        String label = (control.label() + " " + control.locator()).trim();
        if ("search".equals(kind)) {
            return false;
        }
        if (action == BrowserAction.SUBMIT) {
            // Enter in a field outside a form only triggers page scripts; inside a form it sends the form.
            return !control.form().isEmpty() || !control.target().isEmpty();
        }
        if ("checkbox".equals(kind) || "radio".equals(kind) || "select".equals(kind) || "tab".equals(kind)
                || "toggle".equals(kind) || "input".equals(kind)) {
            return false;
        }
        if (CHANGING_LABEL.matcher(label).matches()) {
            return true;
        }
        if (SEARCH_LABEL.matcher(label).matches()) {
            return false;
        }
        // A button that sends its form somewhere: a form submit, unless it is plainly a search.
        return "button".equals(kind) && !control.target().isEmpty();
    }

    /**
     * The arguments the tool hands the gate for this action: only runtime data, nothing from the model.
     * {@code pageUrl} is the page the control is on (may be null).
     */
    public static Map<String, Object> request(String controlId, BrowserControl control, BrowserAction action,
                                              String pageUrl) {
        boolean changes = changesData(control, action);
        String target = control == null || control.target().isEmpty() ? pageUrl : control.target();
        String host = host(target);
        Map<String, Object> arguments = new HashMap<String, Object>();
        arguments.put(ARG_ACTION, changes ? CHANGE : OPERATE);
        arguments.put(ARG_HOST, host);
        arguments.put(ARG_TARGET, (host == null ? "" : host) + "#" + (control == null ? controlId : control.id()));
        arguments.put(ARG_DESCRIPTION, describe(controlId, control, action, host));
        return Collections.unmodifiableMap(arguments);
    }

    /**
     * Resolver for {@code browser_act}: arguments without {@link #request} data (the plain model call the catalog
     * checks first) count as {@value #OPERATE}; the binding check with runtime data follows inside the tool.
     */
    public static ApprovalTargetResolver resolver() {
        return (toolName, arguments) -> {
            Object action = arguments.get(ARG_ACTION);
            if (!CHANGE.equals(action)) {
                return new ApprovalTarget(OPERATE, null, null, null, "Browser bedienen");
            }
            Object target = arguments.get(ARG_TARGET);
            Object description = arguments.get(ARG_DESCRIPTION);
            return new ApprovalTarget(CHANGE, null, target == null ? null : String.valueOf(target), null,
                    description == null ? "" : String.valueOf(description));
        };
    }

    private static String describe(String controlId, BrowserControl control, BrowserAction action, String host) {
        String label = control == null || control.label().isEmpty() ? controlId : control.label();
        StringBuilder text = new StringBuilder("Browser: „").append(label).append("“ ")
                .append(action == BrowserAction.SUBMIT ? "absenden" : "anklicken");
        if (control != null && !control.form().isEmpty()) {
            text.append(" (Formular „").append(control.form()).append("“)");
        }
        if (host != null) {
            text.append(" auf ").append(host);
        }
        return text.append(" – kann Daten auf der Seite ändern").toString();
    }

    private static String host(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
