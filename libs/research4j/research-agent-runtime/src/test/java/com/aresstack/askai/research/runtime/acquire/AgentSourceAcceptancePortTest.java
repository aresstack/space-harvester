package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

/** The agent port is exactly the inline {@code source_accept} call the loop always made. */
public class AgentSourceAcceptancePortTest {

    @Test
    public void delegatesToTheSourceAcceptToolWithTheCaptureId() throws Exception {
        final String[] seenTool = new String[1];
        final Object[] seenCaptureId = new Object[1];
        ToolInvoker research = new ToolInvoker() {
            public String call(String tool, Map<String, Object> args) {
                seenTool[0] = tool;
                seenCaptureId[0] = args.get("capture_id");
                return "status=ACCEPTED source_id=source-1 duplicate=false";
            }
        };

        String result = new AgentSourceAcceptancePort(research).accept("cap-42");

        assertEquals("source_accept", seenTool[0]);
        assertEquals("cap-42", seenCaptureId[0]);
        assertEquals("status=ACCEPTED source_id=source-1 duplicate=false", result);
    }
}
