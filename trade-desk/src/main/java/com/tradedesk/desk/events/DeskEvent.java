package com.tradedesk.desk.events;

/**
 * One server-sent event for this desk's UI: {@code {"type": "invoice.updated", "data": {...}}}. Each slice adds its
 * event types to contracts/desk-api.md.
 */
public record DeskEvent(String type, Object data) {
}
