package com.coldchain.common;

public record ApiResponse<T>(int code, String message, T data, String errorCode) {

    public ApiResponse(int code, String message, T data) {
        this(code, message, data, null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "OK", data, null);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "OK", null, null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, null);
    }

    public static <T> ApiResponse<T> fail(int code, String errorCode, String message) {
        return new ApiResponse<>(code, message, null, errorCode);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ApiResponse<?> fail(int code, String errorCode, String message, Object data) {
        return new ApiResponse(code, message, data, errorCode);
    }
}
