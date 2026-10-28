package com.tradedesk.desk;

import com.tradedesk.desk.api.SessionController;
import com.tradedesk.desk.config.DeskProperties;
import com.tradedesk.desk.security.SecurityConfig;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(DeskProperties.class)
@TestPropertySource(properties = { "desk.id=roaster", "desk.name=Roaster & Co", "desk.domain=roaster.test",
		"desk.location=37.7749,-122.4194", "desk.region=US-CA", "desk.time-zone=America/Los_Angeles",
		"desk.demo-password=test-password" })
class SessionSecurityTest {

	@Autowired
	MockMvc mvc;

	@Test
	void meIsUnauthorizedBeforeLoginWithoutRedirect() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void csrfTokenIsAvailableBeforeLogin() throws Exception {
		mvc.perform(get("/api/csrf"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
			.andExpect(jsonPath("$.token").isNotEmpty());
	}

	@Test
	void wrongPasswordGets401() throws Exception {
		mvc.perform(formLogin("/api/login").user("roaster").password("nope")).andExpect(status().isUnauthorized());
	}

	@Test
	void loginWithoutCsrfTokenIsRejected() throws Exception {
		mvc.perform(post("/api/login").param("username", "roaster").param("password", "test-password"))
			.andExpect(status().isForbidden());
	}

	@Test
	void loginThenMeReturnsTheDeskUser() throws Exception {
		MockHttpSession session = (MockHttpSession) mvc
			.perform(formLogin("/api/login").user("roaster").password("test-password"))
			.andExpect(status().isNoContent())
			.andReturn()
			.getRequest()
			.getSession();

		mvc.perform(get("/api/me").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("roaster"))
			.andExpect(jsonPath("$.deskName").value("Roaster & Co"))
			.andExpect(jsonPath("$.simulated").value(false));

		mvc.perform(post("/api/logout").session(session).with(csrf())).andExpect(status().isNoContent());
		mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
	}

}
