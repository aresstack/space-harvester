package com.aresstack.askai.research.runtime.team.mailbox;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;

import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

/**
 * The two tools of plan section 2.4 for one calling task: {@code send_message(recipient, message)} and
 * {@code finish_task(summary)}. The caller's identity is bound here by Java, never taken from the model's
 * arguments. A model that is bad at tool calls writes the labelled lines instead ({@link WorkerAnswerParser});
 * both paths end in the same {@link AgentMailbox} methods. The page-worker role's catalog picks them up in T4.
 */
public final class MailboxTools {

    public static final String SEND_MESSAGE = "send_message";
    public static final String FINISH_TASK = "finish_task";

    private MailboxTools() {
    }

    /** Both tools for {@code taskId}, a registered worker task of {@code mailbox}. */
    public static List<McpToolContribution> forTask(AgentMailbox mailbox, String taskId) {
        return Arrays.asList(sendMessage(mailbox, taskId), finishTask(mailbox, taskId));
    }

    public static McpToolContribution sendMessage(final AgentMailbox mailbox, final String taskId) {
        return McpToolContribution.of(SEND_MESSAGE,
                "Send a short message to the orchestrator or to another worker. It is delivered later; do not wait "
                        + "for an answer. Example: recipient=\"orchestrator\", message=\"This page is only vendor "
                        + "advertising. We still need studies on data protection.\"",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String recipient = call.getString("recipient");
                        String message = call.getString("message");
                        if (recipient == null || recipient.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: recipient — example: recipient=\"orchestrator\"");
                        }
                        try {
                            SendReceipt receipt = mailbox.send(taskId, recipient, message);
                            return receipt.isAccepted() ? McpToolResult.ok(receipt.getReply())
                                    : McpToolResult.error(receipt.getReply());
                        } catch (UncheckedIOException failed) {
                            return McpToolResult.error("Tool failed: the message could not be saved.");
                        }
                    }
                },
                McpToolParameter.string("recipient", true, "orchestrator, or worker <number>"),
                McpToolParameter.string("message", true, "The message in plain words"));
    }

    public static McpToolContribution finishTask(final AgentMailbox mailbox, final String taskId) {
        return McpToolContribution.of(FINISH_TASK,
                "Finish your task and report the result in plain words. Put open questions into the summary.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        try {
                            String reply = mailbox.finishTask(taskId, call.getString("summary"));
                            return reply.startsWith("Not ") ? McpToolResult.error(reply) : McpToolResult.ok(reply);
                        } catch (UncheckedIOException failed) {
                            return McpToolResult.error("Tool failed: the result could not be saved.");
                        }
                    }
                },
                McpToolParameter.string("summary", true, "What you found and what is still open"));
    }
}
