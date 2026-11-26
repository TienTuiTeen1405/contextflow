package com.contextflow.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiProblemHandler {
    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ErrorEnvelope> problem(ApiProblem problem, HttpServletRequest request) {
        return response(problem.code(), problem.getMessage(), request);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorEnvelope> invalid(Exception ignored, HttpServletRequest request) {
        return response(
                ErrorCode.VALIDATION_ERROR, "Invalid request fields or parameter values.", request);
    }

    private ResponseEntity<ErrorEnvelope> response(
            ErrorCode code, String message, HttpServletRequest request) {
        UUID correlation =
                UUID.fromString((String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        return ResponseEntity.status(code.httpStatus())
                .body(
                        new ErrorEnvelope(
                                new ApiError(code, message, code.retryable(), correlation, null)));
    }
}
