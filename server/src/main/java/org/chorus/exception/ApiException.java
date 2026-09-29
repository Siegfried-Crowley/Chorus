package org.chorus.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类 — 携带明确的 HTTP 状态码与面向客户端的错误消息。
 *
 * <p>统一替代原先在 Service 层抛 {@code RuntimeException("xxx")}、
 * 再由 GlobalExceptionHandler 用字符串匹配猜测状态码的写法。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 便捷方法:资源不存在 → 404 */
    public static ApiException notFound(String message) {
        return new NotFoundException(message);
    }

    /** 便捷方法:未认证 → 401 */
    public static ApiException unauthorized(String message) {
        return new UnauthorizedException(message);
    }

    /** 便捷方法:权限不足 → 403 */
    public static ApiException forbidden(String message) {
        return new ForbiddenException(message);
    }

    /** 便捷方法:参数/状态不合法 → 400 */
    public static ApiException badRequest(String message) {
        return new BadRequestException(message);
    }

    /** 便捷方法:服务器内部错误 → 500 */
    public static ApiException internalError(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
