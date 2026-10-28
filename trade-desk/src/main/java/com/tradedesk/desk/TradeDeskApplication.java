package com.tradedesk.desk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One trade desk. The same image runs once per business (café, roaster, simulated suppliers); {@code DESK_*}
 * variables in docker-compose.yml decide which business this instance is.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TradeDeskApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradeDeskApplication.class, args);
	}

}
