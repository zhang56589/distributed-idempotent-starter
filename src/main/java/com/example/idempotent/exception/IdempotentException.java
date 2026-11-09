package com.example.idempotent.exception;

/**
 * <p>幂等异常 —— 当请求被判定为重复时抛出</p>
 * <p>应由全局异常处理器捕获并返回友好 JSON</p>
 *
 * @author distributed-idempotent-starter
 */
public class IdempotentException extends RuntimeException {

    /**
     * 默认错误码
     */
    public static final String DEFAULT_CODE = "IDE_4009";

    private final String code;

    public IdempotentException(String message) {
        super(message);
        this.code = DEFAULT_CODE;
    }

    public IdempotentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public IdempotentException(String message, Throwable cause) {
        super(message, cause);
        this.code = DEFAULT_CODE;
    }

    public String getCode() {
        return code;
    }
}
