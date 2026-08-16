package com.pv.cases.exception;

/**
 * Raised when a case id does not resolve to a stored case.
 *
 * <p>Carries the offending id rather than only a formatted message, so
 * {@link GlobalExceptionHandler} can put it in the response body without having
 * to parse it back out of the message text.
 *
 * <p>Intentionally free of {@code @ResponseStatus}: the HTTP mapping lives in the
 * handler, which keeps this exception usable from non-web callers.
 */
public class CaseNotFoundException extends RuntimeException {

	private final String caseId;

	public CaseNotFoundException(String caseId) {
		super("Case not found: " + caseId);
		this.caseId = caseId;
	}

	public String getCaseId() {
		return caseId;
	}
}
