package org.chorus.exception;

import org.springframework.http.HttpStatus;

/** 400 — 请求参数或业务状态不合法 */
public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
