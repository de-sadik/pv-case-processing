package com.pv.cases.exception;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Translates exceptions into the API's error response shape.
 *
 * <p>Response bodies are declared as records here rather than assembled as maps,
 * so the wire shape of every error is visible in one place and cannot drift
 * key-by-key across handlers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(CaseNotFoundException.class)
	public ResponseEntity<CaseNotFoundResponse> handleCaseNotFound(CaseNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new CaseNotFoundResponse("Case not found", ex.getCaseId()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ValidationFailureResponse> handleValidationFailure(MethodArgumentNotValidException ex) {
		List<FieldDetail> details = ex.getFieldErrors().stream()
				.map(error -> new FieldDetail(error.getField(), error.getDefaultMessage()))
				.toList();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ValidationFailureResponse("Validation failed", details));
	}

	/**
	 * Catch-all. The exception is logged in full before the response is built —
	 * the client is told nothing beyond "internal server error", so the log is the
	 * only remaining record of what actually happened.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("Unhandled exception while processing request", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ErrorResponse("Internal server error"));
	}

	/** {@code {"error": "Case not found", "case_id": "PV-2026-0451"}} */
	private record CaseNotFoundResponse(String error, @JsonProperty("case_id") String caseId) {
	}

	/** {@code {"error": "Validation failed", "details": [...]}} */
	private record ValidationFailureResponse(String error, List<FieldDetail> details) {
	}

	/** {@code {"field": "question", "message": "must not be blank"}} */
	private record FieldDetail(String field, String message) {
	}

	/** {@code {"error": "Internal server error"}} */
	private record ErrorResponse(String error) {
	}
}
