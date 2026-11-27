package com.aresstack.askai.research.runtime.role;

import com.aresstack.askai.agent.model.port.ModelSlot;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The role declaration: prompt, tools, output, slot and context are explicit and validated. */
public class ResearchRoleTest {

    private static final ModelSlot JUDGE = ModelSlot.of("judge");

    @Test
    public void aRoleCarriesItsPromptToolsOutputSlotAndContext() {
        ResearchRole role = ResearchRole.builder("source-verifier", JUDGE)
                .purpose("checks a source")
                .systemPrompt("  Check the source.\n")
                .tools(RoleToolCatalog.of("source_list", "artifact_read"))
                .output(RoleOutput.schema("{\"type\":\"object\"}", "VerdictCodec"))
                .contextTokens(8192)
                .build();

        assertEquals("source-verifier", role.getId());
        assertEquals(JUDGE, role.getSlot());
        assertEquals("the prompt is kept verbatim, never trimmed", "  Check the source.\n",
                role.getSystemPrompt());
        assertEquals(ResearchRole.PromptSource.ROLE, role.getPromptSource());
        assertEquals(2, role.getTools().getToolNames().size());
        assertEquals(RoleOutput.Kind.JSON_SCHEMA, role.getOutput().getKind());
        assertEquals(8192, role.getContextTokens());
    }

    @Test
    public void contextZeroMeansTheSlotDefaultAndCanBeOverriddenPerRole() {
        ResearchRole role = ResearchRole.builder("judge", JUDGE).systemPrompt("x")
                .output(RoleOutput.text("Labels")).build();
        assertEquals(0, role.getContextTokens());

        ResearchRole wide = role.withContextTokens(32768);
        assertEquals(32768, wide.getContextTokens());
        assertEquals(role.getSystemPrompt(), wide.getSystemPrompt());
        assertEquals(0, wide.withContextTokens(0).getContextTokens());
    }

    @Test
    public void aCallerPromptedRoleDeclaresNoPrompt() {
        ResearchRole role = ResearchRole.builder("repair", JUDGE).promptFromCaller()
                .output(RoleOutput.json("RepairCodec")).build();
        assertEquals(ResearchRole.PromptSource.CALLER, role.getPromptSource());
        assertEquals("", role.getSystemPrompt());
    }

    @Test
    public void incompleteOrInvalidRolesAreRejected() {
        expectRejected(new Runnable() {
            public void run() {
                ResearchRole.builder("Bad Id", JUDGE);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ResearchRole.builder("judge", null);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ResearchRole.builder("judge", JUDGE).output(RoleOutput.text("Labels")).build();
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ResearchRole.builder("judge", JUDGE).systemPrompt("x").build();
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ResearchRole.builder("judge", JUDGE).systemPrompt("x").contextTokens(-1);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ResearchRole.builder("judge", JUDGE).systemPrompt("   ");
            }
        });
    }

    @Test
    public void outputShapesNameTheirContract() {
        assertTrue(RoleOutput.schemaPerRequest("Validator").isSchemaPerRequest());
        assertEquals("{\"type\":\"object\"}", RoleOutput.schema("{\"type\":\"object\"}", "C").getSchemaJson());
        assertEquals("Parser", RoleOutput.text("Parser").getContract());
    }

    private static void expectRejected(Runnable action) {
        try {
            action.run();
            fail("expected a rejection");
        } catch (IllegalArgumentException expected) {
            // the declaration is invalid
        } catch (IllegalStateException expected) {
            // the declaration is incomplete
        }
    }
}
