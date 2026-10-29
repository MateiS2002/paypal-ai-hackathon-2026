package com.tradedesk.desk.config;

import java.math.BigDecimal;
import java.time.ZoneId;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Who this desk is. Bound from the {@code DESK_*} environment variables set per service in docker-compose.yml.
 * The app refuses to start if any of these are missing or malformed, including an unknown {@code DESK_TIME_ZONE}.
 *
 * @param id short id, also the demo login username and the session cookie prefix (for example {@code roaster})
 * @param name display name shown in the UI
 * @param domain the business's domain on the compose network (for example {@code roaster.test})
 * @param location {@code lat,lon}
 * @param region ISO 3166-2 code such as {@code US-CA}
 * @param timeZone IANA zone such as {@code America/Los_Angeles}
 * @param invoiceApprovalAbove messy-order invoices above this USD amount, in another currency, or on a desk with
 *     no limit set wait for the owner's approval (roaster only, used by S7)
 * @param persona free-text personality for simulated suppliers
 * @param simulated true for SIMULATED suppliers, shown as such in the UI
 * @param demoPassword demo login password, from {@code DESK_DEMO_PASSWORD} in .env
 */
@Validated
@ConfigurationProperties("desk")
public record DeskProperties(
		@NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,30}") String id,
		@NotBlank String name,
		@NotBlank String domain,
		@NotBlank @Pattern(regexp = "-?\\d{1,2}(\\.\\d+)?,-?\\d{1,3}(\\.\\d+)?") String location,
		@NotBlank @Pattern(regexp = "[A-Z]{2}-[A-Z0-9]{1,3}") String region,
		@NotNull ZoneId timeZone,
		@DecimalMin("0.00") BigDecimal invoiceApprovalAbove,
		String persona,
		boolean simulated,
		@NotBlank String demoPassword) {

	@Override
	public String toString() {
		// Keep the demo password out of logs and actuator output.
		return "DeskProperties[id=%s, name=%s, domain=%s, simulated=%s]".formatted(id, name, domain, simulated);
	}

}
