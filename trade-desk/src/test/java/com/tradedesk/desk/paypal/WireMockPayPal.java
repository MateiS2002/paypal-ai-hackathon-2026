package com.tradedesk.desk.paypal;

import java.time.Clock;

import com.github.tomakehurst.wiremock.WireMockServer;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** Points the desk's PayPal client at a WireMock server instead of the sandbox. Tests only. */
@TestConfiguration(proxyBeanMethods = false)
public class WireMockPayPal {

	public static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

	static {
		SERVER.start();
	}

	@Bean
	@Primary
	PayPalClient wireMockPayPalClient(PayPalProperties properties, JsonMapper json, Clock clock) {
		return new PayPalClient(properties, json, clock, SERVER.baseUrl());
	}

}
