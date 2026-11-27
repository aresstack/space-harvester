package com.aresstack.askai.research.runtime.team.mailbox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lines of the mailbox journal. {@code kind=message} is a full snapshot of one message (the last snapshot per id
 * wins); {@code kind=worker} is one registration (display number, task id, live).
 */
final class MailboxJournalCodec {

    private MailboxJournalCodec() {
    }

    static String encode(AgentMessage m) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "message");
        o.addProperty("id", m.getId());
        o.addProperty("runId", m.getRunId());
        o.addProperty("from", m.getSender().key());
        if (m.getSenderTaskId() != null) {
            o.addProperty("fromTask", m.getSenderTaskId());
        }
        o.addProperty("to", m.getRecipient().key());
        if (m.getRecipientTaskId() != null) {
            o.addProperty("toTask", m.getRecipientTaskId());
        }
        o.addProperty("text", m.getText());
        o.addProperty("at", m.getCreatedAtMillis());
        o.addProperty("state", m.getState().name());
        if (m.getNote() != null) {
            o.addProperty("note", m.getNote());
        }
        if (m.isHandedOut()) {
            o.addProperty("handedOut", true);
        }
        return o.toString();
    }

    static String encodeWorker(int number, String taskId, boolean live) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "worker");
        o.addProperty("number", number);
        o.addProperty("taskId", taskId);
        o.addProperty("live", live);
        return o.toString();
    }

    static JsonObject parse(String line) {
        return JsonParser.parseString(line).getAsJsonObject();
    }

    static AgentMessage decodeMessage(JsonObject o) {
        AgentAddress from = AgentAddress.parse(o.get("from").getAsString());
        AgentAddress to = AgentAddress.parse(o.get("to").getAsString());
        if (from == null || to == null) {
            throw new IllegalStateException("unreadable address in message " + o.get("id"));
        }
        return new AgentMessage(o.get("id").getAsString(), o.get("runId").getAsString(), from,
                string(o, "fromTask"), to, string(o, "toTask"), o.get("text").getAsString(),
                o.get("at").getAsLong(), DeliveryState.valueOf(o.get("state").getAsString()), string(o, "note"),
                o.has("handedOut") && o.get("handedOut").getAsBoolean());
    }

    private static String string(JsonObject o, String name) {
        return o.has(name) && !o.get(name).isJsonNull() ? o.get(name).getAsString() : null;
    }
}
