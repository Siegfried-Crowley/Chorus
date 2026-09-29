package org.chorus.exception;

import org.springframework.http.HttpStatus;

/** 403 — 已认证但权限不足(或不是成员) */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
