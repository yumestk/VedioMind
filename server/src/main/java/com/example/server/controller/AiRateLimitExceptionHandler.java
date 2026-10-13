package com.example.server.controller;

import com.example.server.service.ai.AiRateLimitService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AiRateLimitExceptionHandler {

    @ExceptionHandler(AiRateLimitService.AiRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleExceeded(
            AiRateLimitService.AiRateLimitExceededException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, exception.getMessage()
        );
        problem.setTitle("AI 请求过于频繁");
        problem.setProperty("code", "AI_RATE_LIMITED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.retryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(AiRateLimitService.AiRateLimitUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleUnavailable(
            AiRateLimitService.AiRateLimitUnavailableException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage()
        );
        problem.setTitle("AI 服务暂时不可用");
        problem.setProperty("code", "AI_RATE_LIMIT_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
