package org.chorus.exception;

import org.springframework.http.HttpStatus;

/** 404 — 资源不存在 */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
