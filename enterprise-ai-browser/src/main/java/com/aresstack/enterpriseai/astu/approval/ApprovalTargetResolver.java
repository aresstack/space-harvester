package com.aresstack.enterpriseai.astu.approval;

import java.util.Map;

/**
 * Determines, without side effects, what a tool call would act on: action, scope, owner-checked target id and
 * version. Implementations look the target up in trusted data (store, session) instead of believing the model's
 * arguments. An exception means the target is unknown; the gate then does not run the tool.
 */
public interface ApprovalTargetResolver {

    /** Every call is the plain {@link ApprovalTarget#INVOKE} of the tool. */
    static ApprovalTargetResolver invoke() {
        return (toolName, arguments) -> ApprovalTarget.invoke();
    }

    /** @param arguments the call's arguments as sent by the model; never {@code null} */
    ApprovalTarget resolve(String toolName, Map<String, Object> arguments);
}
