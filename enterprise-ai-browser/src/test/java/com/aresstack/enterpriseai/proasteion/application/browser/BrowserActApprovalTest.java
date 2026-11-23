package com.aresstack.enterpriseai.proasteion.application.browser;

import com.aresstack.enterpriseai.astu.approval.ApprovalPolicy;
import com.aresstack.enterpriseai.astu.approval.ApprovalRules;
import com.aresstack.enterpriseai.astu.approval.ApprovalTarget;
import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserControl;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BrowserActApprovalTest {

    private static BrowserControl button(String label, String target) {
        return new BrowserControl("ctl-1", "button", label, "", "", target);
    }

    private static BrowserControl field(String kind, String form) {
        return new BrowserControl("ctl-2", kind, "Betrag", "", "", "", form, false, "", "");
    }

    @Test
    public void savingSendingAndDeletingButtonsChangeData() {
        for (String label : new String[]{"Speichern", "Absenden", "Löschen", "Rechnung anlegen", "Jetzt buchen",
                "Bestätigen", "Save", "Submit", "Delete"}) {
            assertTrue(label, BrowserActApproval.changesData(button(label, ""), BrowserAction.CLICK));
        }
    }

    @Test
    public void readingSearchingAndTypingStayFree() {
        assertFalse(BrowserActApproval.changesData(button("Mehr anzeigen", ""), BrowserAction.CLICK));
        assertFalse(BrowserActApproval.changesData(button("Suchen", "https://a.example/suche"), BrowserAction.CLICK));
        assertFalse(BrowserActApproval.changesData(field("search", "Suche"), BrowserAction.SUBMIT));
        assertFalse(BrowserActApproval.changesData(field("input", "Neue eRechnung"), BrowserAction.FILL));
        assertFalse(BrowserActApproval.changesData(field("select", "Neue eRechnung"), BrowserAction.SELECT));
        assertFalse(BrowserActApproval.changesData(field("checkbox", "Neue eRechnung"), BrowserAction.CLICK));
        assertFalse(BrowserActApproval.changesData(field("input", ""), BrowserAction.SUBMIT));
    }

    @Test
    public void formSubmitsAndUnknownControlsAsk() {
        assertTrue(BrowserActApproval.changesData(field("input", "Neue eRechnung"), BrowserAction.SUBMIT));
        assertTrue(BrowserActApproval.changesData(button("Weiter", "https://a.example/schritt2"),
                BrowserAction.CLICK));
        assertTrue(BrowserActApproval.changesData(null, BrowserAction.CLICK));
        assertTrue(BrowserActApproval.changesData(null, BrowserAction.SUBMIT));
        assertFalse(BrowserActApproval.changesData(null, BrowserAction.FILL));
    }

    @Test
    public void defaultsAskForChangesAndAllowTheRest() {
        Map<String, ApprovalPolicy> defaults = BrowserActApproval.defaultPolicies();
        assertEquals(ApprovalPolicy.ASK, defaults.get(BrowserActApproval.CHANGE));
        assertEquals(ApprovalPolicy.ALLOW, defaults.get(BrowserActApproval.OPERATE));
    }

    @Test
    public void resolverTrustsOnlyTheRuntimeRequestAndNamesHostAndForm() {
        ApprovalTarget plain = BrowserActApproval.resolver().resolve("browser_act", new HashMap<String, Object>());
        assertEquals(BrowserActApproval.OPERATE, plain.action());

        Map<String, Object> request = BrowserActApproval.request("ctl-2", field("input", "Neue eRechnung"),
                BrowserAction.SUBMIT, "https://Intranet.Example/rechnung");
        ApprovalTarget target = BrowserActApproval.resolver().resolve("browser_act", request);
        assertEquals(BrowserActApproval.CHANGE, target.action());
        assertEquals("intranet.example#ctl-2", target.targetId());
        assertTrue(target.description(), target.description().contains("Formular „Neue eRechnung“"));
        assertTrue(target.description(), target.description().contains("intranet.example"));
        assertEquals(ApprovalPolicy.ASK, new ApprovalRules(BrowserActApprovalTest.withDefaults())
                .policy("browser_act", target));
    }

    private static Map<String, ApprovalPolicy> withDefaults() {
        Map<String, ApprovalPolicy> rules = new HashMap<String, ApprovalPolicy>();
        for (Map.Entry<String, ApprovalPolicy> rule : BrowserActApproval.defaultPolicies().entrySet()) {
            rules.put(ApprovalRules.key("browser_act", rule.getKey(), null), rule.getValue());
        }
        return rules;
    }
}
