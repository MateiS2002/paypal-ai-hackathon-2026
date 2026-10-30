package com.tradedesk.desk.need;

import java.util.UUID;

/** Published when every supplier asked for a need has answered, refused or timed out. S3 counters from here. */
public record NeedCompleted(UUID needId) {

}
