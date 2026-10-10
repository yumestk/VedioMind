package com.example.server.service.external;

import org.springframework.http.HttpStatus;

public class ExternalVideoException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    private ExternalVideoException(String code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public static ExternalVideoException invalidUrl(String message) {
        return new ExternalVideoException("INVALID_VIDEO_URL", HttpStatus.BAD_REQUEST, message, null);
    }

    public static ExternalVideoException unsupportedPlatform() {
        return new ExternalVideoException(
                "UNSUPPORTED_PLATFORM",
                HttpStatus.BAD_REQUEST,
                "目前仅支持 Bilibili 和 YouTube 公共视频链接。",
                null
        );
    }

    public static ExternalVideoException subtitleUnavailable() {
        return new ExternalVideoException(
                "SUBTITLE_NOT_AVAILABLE",
                HttpStatus.UNPROCESSABLE_ENTITY,
                "该视频没有可用的平台字幕，请改用本地上传。",
                null
        );
    }

    public static ExternalVideoException platformUnavailable(Throwable cause) {
        return new ExternalVideoException(
                "EXTERNAL_PLATFORM_UNAVAILABLE",
                HttpStatus.BAD_GATEWAY,
                "视频平台暂时无法访问，请稍后重试。",
                cause
        );
    }

    public static ExternalVideoException timeout(Throwable cause) {
        return new ExternalVideoException(
                "EXTERNAL_RESOLVE_TIMEOUT",
                HttpStatus.GATEWAY_TIMEOUT,
                "视频链接解析超时，请稍后重试。",
                cause
        );
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
