package com.coldchain.common;

public class BusinessException extends RuntimeException {
    private final int code;
    /** 机器可读错误码，如 BOX_NOT_FOUND / DATA_INSUFFICIENT / INTERNAL_ERROR */
    private final String errorCode;
    /** 可选的错误附带数据（如已持久化的不可评估结果） */
    private final transient Object data;

    public BusinessException(String message) {
        this(400, "BAD_REQUEST", message, null);
    }

    public BusinessException(int code, String message) {
        this(code, defaultErrorCode(code), message, null);
    }

    public BusinessException(int code, String errorCode, String message) {
        this(code, errorCode, message, null);
    }

    public BusinessException(int code, String errorCode, String message, Object data) {
        super(message);
        this.code = code;
        this.errorCode = errorCode;
        this.data = data;
    }

    private static String defaultErrorCode(int code) {
        return switch (code) {
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 422 -> "DATA_INSUFFICIENT";
            case 500 -> "INTERNAL_ERROR";
            default -> "BAD_REQUEST";
        };
    }

    public int getCode() {
        return code;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Object getData() {
        return data;
    }
}
