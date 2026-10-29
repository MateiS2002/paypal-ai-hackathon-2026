package com.tradedesk.desk.api;

import com.tradedesk.desk.invoice.IdempotencyConflictException;
import com.tradedesk.desk.invoice.InvalidInvoiceException;
import com.tradedesk.desk.llm.LlmException;
import com.tradedesk.desk.llm.LlmNotConfiguredException;
import com.tradedesk.desk.need.InvalidNeedException;
import com.tradedesk.desk.paypal.PayPalException;
import com.tradedesk.desk.paypal.PayPalNotConfiguredException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns failures into RFC 9457 problem bodies whose {@code detail} the UI can show as is: no stack traces, no
 * secrets (see contracts/desk-api.md, Errors).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler
	ProblemDetail invalidInvoice(InvalidInvoiceException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid invoice", ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail invalidNeed(InvalidNeedException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid need", ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail llm(LlmException ex) {
		return problem(HttpStatus.BAD_GATEWAY, "AI model error", ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail llmNotConfigured(LlmNotConfiguredException ex) {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "AI model not configured", ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail unreadableBody(HttpMessageNotReadableException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request",
				"The request body is not valid. Amounts must be decimal strings like \"12.50\" with a 3-letter "
						+ "currency code.");
	}

	@ExceptionHandler
	ProblemDetail missingHeader(MissingRequestHeaderException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", "The " + ex.getHeaderName() + " header is required.");
	}

	@ExceptionHandler
	ProblemDetail badArgument(MethodArgumentTypeMismatchException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", "'" + ex.getName() + "' must be a UUID.");
	}

	@ExceptionHandler
	ProblemDetail idempotencyConflict(IdempotencyConflictException ex) {
		return problem(HttpStatus.CONFLICT, "Idempotency-Key reused", ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail payPal(PayPalException ex) {
		return problem(HttpStatus.BAD_GATEWAY, "PayPal error", ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail payPalNotConfigured(PayPalNotConfiguredException ex) {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "PayPal not configured", ex.getMessage());
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return problem;
	}

}
