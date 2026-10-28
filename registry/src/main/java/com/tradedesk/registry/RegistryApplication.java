package com.tradedesk.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The network registry: businesses enroll, it verifies their agent cards and answers supplier searches (S4, S6), and
 * it issues short-lived scoped tokens and stores message fingerprints (S5). It never sees message contents.
 */
@SpringBootApplication
public class RegistryApplication {

	public static void main(String[] args) {
		SpringApplication.run(RegistryApplication.class, args);
	}

}
