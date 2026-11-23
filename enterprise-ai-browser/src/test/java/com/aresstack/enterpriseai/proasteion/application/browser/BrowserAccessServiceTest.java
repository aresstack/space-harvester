package com.aresstack.enterpriseai.proasteion.application.browser;

import com.aresstack.enterpriseai.astu.approval.ApprovalDecision;
import com.aresstack.enterpriseai.astu.approval.ApprovalPromptPort;
import com.aresstack.enterpriseai.astu.approval.ApprovalRequest;
import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserAccessPolicy;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserAccessConfig;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BrowserAccessServiceTest {

    private final ChatConversationId chat = new ChatConversationId("chat-1");
    private final List<ApprovalRequest> asked = new ArrayList<ApprovalRequest>();
    private final List<String> allowed = new ArrayList<String>();
    private final List<String> blocked = new ArrayList<String>();

    private final BrowserPolicyStore store = new BrowserPolicyStore() {
        @Override
        public void allow(BrowserProfile profile, String host) {
            allowed.add(profile == BrowserProfile.INTERNAL ? "intern:" + host : host);
        }

        @Override
        public void block(BrowserProfile profile, String host) {
            blocked.add(profile == BrowserProfile.INTERNAL ? "intern:" + host : host);
        }
    };

    private BrowserAccessService service(BrowserAccessConfig config, final ApprovalDecision.Outcome answer) {
        ApprovalPromptPort prompt = (request, timeoutMillis) -> {
            asked.add(request);
            return ApprovalDecision.of(request, answer);
        };
        return new BrowserAccessService(() -> BrowserAccessService.policyOf(config), () -> prompt, store, 1000);
    }

    private static BrowserAccessConfig config(boolean allowByDefault, String allow, String block) {
        return new BrowserAccessConfig(allowByDefault, BrowserAccessConfig.split(allow),
                BrowserAccessConfig.split(block));
    }

    private void assertBlocked(BrowserAccessService service, String url) {
        try {
            service.admit(chat, url);
            fail("expected BLOCKED for " + url);
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
    }

    @Test
    public void allowListPassesWithoutQuestion() throws Exception {
        service(config(false, "example.org", null), ApprovalDecision.Outcome.REJECTED)
                .admit(chat, "https://example.org/a");
        assertTrue(asked.isEmpty());
    }

    @Test
    public void blockListWinsEvenInAllowMode() {
        assertBlocked(service(config(true, "example.org", "example.org"), ApprovalDecision.Outcome.APPROVED),
                "https://example.org/");
        assertTrue(asked.isEmpty());
    }

    @Test
    public void unknownHostAsksWithAlwaysOptions() throws Exception {
        service(config(false, null, null), ApprovalDecision.Outcome.APPROVED).admit(chat, "https://new.example/x");
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).offersAlways());
        assertEquals("new.example", asked.get(0).target().targetId());
        assertTrue(allowed.isEmpty());
    }

    @Test
    public void alwaysAllowPersistsExactHost() throws Exception {
        service(config(false, null, null), ApprovalDecision.Outcome.APPROVED_ALWAYS)
                .admit(chat, "https://WWW.New.Example/x");
        assertEquals(Collections.singletonList("www.new.example"), allowed);
    }

    @Test
    public void alwaysForbidPersistsAndBlocks() {
        assertBlocked(service(config(false, null, null), ApprovalDecision.Outcome.REJECTED_ALWAYS),
                "https://bad.example/");
        assertEquals(Collections.singletonList("bad.example"), blocked);
    }

    @Test
    public void otherAnswersBlockWithoutPersisting() {
        for (ApprovalDecision.Outcome outcome : Arrays.asList(ApprovalDecision.Outcome.REJECTED,
                ApprovalDecision.Outcome.TIMEOUT, ApprovalDecision.Outcome.CANCELLED,
                ApprovalDecision.Outcome.UNAVAILABLE)) {
            assertBlocked(service(config(false, null, null), outcome), "https://x.example/");
        }
        assertTrue(allowed.isEmpty());
        assertTrue(blocked.isEmpty());
    }

    @Test
    public void allowModeAndNonHttpSchemesPass() throws Exception {
        service(config(true, null, null), ApprovalDecision.Outcome.REJECTED).admit(chat, "https://any.example/");
        service(config(false, null, null), ApprovalDecision.Outcome.REJECTED).admit(chat, "about:blank");
        assertTrue(asked.isEmpty());
        assertEquals(BrowserAccessPolicy.Mode.BLOCK_BY_DEFAULT,
                BrowserAccessService.policyOf(BrowserAccessConfig.defaults()).mode());
    }

    @Test
    public void theChatsActiveProfileDecidesAndAlwaysAnswersGoIntoItsList() throws Exception {
        final BrowserAccessConfig both = config(true, "", "").withInternal(config(false, "intranet.example", ""));
        final ChatConversationId internalChat = new ChatConversationId("chat-intern");
        ApprovalPromptPort prompt = (request, timeoutMillis) -> {
            asked.add(request);
            return ApprovalDecision.of(request, ApprovalDecision.Outcome.APPROVED_ALWAYS);
        };
        BrowserAccessService service = new BrowserAccessService(
                c -> internalChat.equals(c) ? BrowserProfile.INTERNAL : BrowserProfile.EXTERNAL,
                profile -> BrowserAccessService.policyOf(both.profile(profile)), () -> prompt, store, 1000);

        service.admit(chat, "https://public.example/");
        assertTrue("external profile allows by default", asked.isEmpty());
        service.admit(internalChat, "https://intranet.example/start");
        assertTrue("internal whitelist passes", asked.isEmpty());
        service.admit(internalChat, "https://other.example/");
        assertEquals(1, asked.size());
        assertEquals(java.util.Collections.singletonList("intern:other.example"), allowed);
    }
}
