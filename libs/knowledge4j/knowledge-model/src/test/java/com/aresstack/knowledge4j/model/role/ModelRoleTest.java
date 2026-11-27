package com.aresstack.knowledge4j.model.role;

import com.aresstack.knowledge4j.model.ModelSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** The role declaration: prompt, tools, output, slot and context are explicit and validated. */
public class ModelRoleTest {

    private static final ModelSlot JUDGE = ModelSlot.of("judge");

    @Test
    public void aRoleCarriesItsPromptToolsOutputSlotAndContext() {
        ModelRole role = ModelRole.builder("source-verifier", JUDGE)
                .purpose("checks a source")
                .systemPrompt("  Check the source.\n")
                .tools(RoleToolCatalog.of("source_list", "artifact_read"))
                .output(RoleOutput.schema("{\"type\":\"object\"}", "VerdictCodec"))
                .contextTokens(8192)
                .build();

        assertEquals("source-verifier", role.getId());
        assertEquals(JUDGE, role.getSlot());
        assertEquals("  Check the source.\n",
                role.getSystemPrompt(), "the prompt is kept verbatim, never trimmed");
        assertEquals(ModelRole.PromptSource.ROLE, role.getPromptSource());
        assertEquals(2, role.getTools().getToolNames().size());
        assertEquals(RoleOutput.Kind.JSON_SCHEMA, role.getOutput().getKind());
        assertEquals(8192, role.getContextTokens());
    }

    @Test
    public void contextZeroMeansTheSlotDefaultAndCanBeOverriddenPerRole() {
        ModelRole role = ModelRole.builder("judge", JUDGE).systemPrompt("x")
                .output(RoleOutput.text("Labels")).build();
        assertEquals(0, role.getContextTokens());

        ModelRole wide = role.withContextTokens(32768);
        assertEquals(32768, wide.getContextTokens());
        assertEquals(role.getSystemPrompt(), wide.getSystemPrompt());
        assertEquals(0, wide.withContextTokens(0).getContextTokens());
    }

    @Test
    public void aCallerPromptedRoleDeclaresNoPrompt() {
        ModelRole role = ModelRole.builder("repair", JUDGE).promptFromCaller()
                .output(RoleOutput.json("RepairCodec")).build();
        assertEquals(ModelRole.PromptSource.CALLER, role.getPromptSource());
        assertEquals("", role.getSystemPrompt());
    }

    @Test
    public void incompleteOrInvalidRolesAreRejected() {
        expectRejected(new Runnable() {
            public void run() {
                ModelRole.builder("Bad Id", JUDGE);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ModelRole.builder("judge", null);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ModelRole.builder("judge", JUDGE).output(RoleOutput.text("Labels")).build();
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ModelRole.builder("judge", JUDGE).systemPrompt("x").build();
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ModelRole.builder("judge", JUDGE).systemPrompt("x").contextTokens(-1);
            }
        });
        expectRejected(new Runnable() {
            public void run() {
                ModelRole.builder("judge", JUDGE).systemPrompt("   ");
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
