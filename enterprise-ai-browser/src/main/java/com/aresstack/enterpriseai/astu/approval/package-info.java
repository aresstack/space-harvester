/**
 * Generic, framework-free tool approvals: which tool call may run without asking ({@link ApprovalPolicy#ALLOW}),
 * only after the user agreed ({@link ApprovalPolicy#ASK}) or never ({@link ApprovalPolicy#DENY}).
 *
 * <p>{@link ToolApprovalGate} is the single check point; the MCP tool handler wrapper in katagogion applies it. The
 * question to the user goes through {@link ApprovalPromptPort}, implemented by the UI.
 */
package com.aresstack.enterpriseai.astu.approval;
