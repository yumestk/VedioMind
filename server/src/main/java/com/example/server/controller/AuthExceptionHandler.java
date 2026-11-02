package com.example.server.controller;

import com.example.server.security.AuthException;

import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@lombok.extern.slf4j.Slf4j
public class AuthExceptionHandler {
    @ExceptionHandler(AuthException.class)
    ResponseEntity<ProblemDetail> auth(AuthException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> duplicate(DuplicateKeyException e) {
        return problem(409, "ACCOUNT_CONFLICT", "账号已存在或请求冲突");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(DataAccessException e) {
        log.warn(
                "Data store unavailable during request, exceptionType={}",
                e.getClass().getSimpleName());
        return problem(503, "DATA_STORE_UNAVAILABLE", "数据服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        return problem(400, "INVALID_INPUT", "输入格式不正确：用户名需为3-100位字母数字或下划线，注册密码需为12-128位");
    }

    private ResponseEntity<ProblemDetail> problem(int status, String code, String detail) {
        var p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        p.setProperty("code", code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(p);
    }
}
