package com.tradedesk.registry;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import(SecurityConfig.class)
class RegistrySecurityTest {

	@Autowired
	MockMvc mvc;

	@Test
	void everythingButHealthIsClosed() throws Exception {
		mvc.perform(get("/api/suppliers")).andExpect(status().isUnauthorized());
	}

}
