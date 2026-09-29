package org.chorus.exception;

import org.springframework.http.HttpStatus;

/** 401 — 未认证或凭据无效 */
public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
