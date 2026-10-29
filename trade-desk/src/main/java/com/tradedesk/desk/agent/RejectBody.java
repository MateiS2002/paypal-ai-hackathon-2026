package com.tradedesk.desk.agent;

import java.util.UUID;

/** The body of a {@code reject} (contracts/agent-messages.md). The reason is plain text, shown as is. */
public record RejectBody(UUID rfqId, String reason) {

	public static final int MAX_REASON = 200;

}
