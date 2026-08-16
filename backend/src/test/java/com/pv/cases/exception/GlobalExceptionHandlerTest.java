package com.pv.cases.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.pv.cases.model.Query;

import jakarta.validation.Valid;

/**
 * Drives the advice through a real MVC dispatch against a throwaway controller,
 * so the assertions cover handler resolution and message conversion rather than
 * just the handler methods in isolation.
 */
class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void caseNotFound_returns404WithTheOffendingCaseId() throws Exception {
		this.mockMvc.perform(get("/test/missing-case"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.error").value("Case not found"))
				.andExpect(jsonPath("$.case_id").value("PV-2026-0451"))
				.andExpect(jsonPath("$.caseId").doesNotExist());
	}

	@Test
	void invalidRequestBody_returns400WithAFieldDetailPerViolation() throws Exception {
		String body = """
				{"case_id":"PV-2026-0451","field_path":"patientage","question":"  "}""";

		this.mockMvc.perform(post("/test/queries").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation failed"))
				.andExpect(jsonPath("$.details").isArray())
				.andExpect(jsonPath("$.details[?(@.field == 'question')].message")
						.value("must not be blank"))
				.andExpect(jsonPath("$.details[?(@.field == 'fieldPath')].message")
						.value("must be a dot-separated section and field, e.g. patient.age"));
	}

	@Test
	void validRequestBody_isNotIntercepted() throws Exception {
		String body = """
				{"case_id":"PV-2026-0451","field_path":"patient.age","question":"Age conflicts?"}""";

		this.mockMvc.perform(post("/test/queries").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk());
	}

	@Test
	void unexpectedException_returns500WithoutLeakingTheCause() throws Exception {
		this.mockMvc.perform(get("/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Internal server error"))
				.andExpect(jsonPath("$.message").doesNotExist())
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("connection string"))));
	}

	/**
	 * Documents a consequence of the catch-all rather than endorsing it:
	 * {@link ValidationException} has no handler of its own, so it falls through to
	 * {@code Exception} and is reported as a 500. If a 400 mapping is added, this
	 * test should be updated to expect it.
	 */
	@Test
	void validationException_currentlyFallsThroughToThe500CatchAll() throws Exception {
		this.mockMvc.perform(get("/test/invalid-state"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Internal server error"));
	}

	/**
	 * Spring raises {@code HttpRequestMethodNotSupportedException} for a wrong verb
	 * and would map it to 405 on its own, but {@code @ExceptionHandler(Exception.class)}
	 * is consulted before Spring's {@code DefaultHandlerExceptionResolver} and claims
	 * it first. Same story for malformed JSON, which should be a 400.
	 */
	@Test
	void springsOwn4xxExceptions_areMaskedAs500ByTheCatchAll() throws Exception {
		this.mockMvc.perform(post("/test/missing-case"))
				.andExpect(status().isInternalServerError());

		this.mockMvc.perform(post("/test/queries").contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isInternalServerError());
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/test/missing-case")
		String missingCase() {
			throw new CaseNotFoundException("PV-2026-0451");
		}

		@GetMapping("/test/boom")
		String boom() {
			throw new IllegalStateException("connection string user=admin password=hunter2");
		}

		@GetMapping("/test/invalid-state")
		String invalidState() {
			throw new ValidationException("follow-up version 5 does not follow stored version 1");
		}

		@PostMapping("/test/queries")
		Query queries(@Valid @RequestBody Query query) {
			return query;
		}
	}
}
