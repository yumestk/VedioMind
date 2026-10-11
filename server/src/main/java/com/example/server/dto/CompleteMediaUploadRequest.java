package com.example.server.dto;

import java.util.List;

public record CompleteMediaUploadRequest(
        List<CompleteUploadPartRequest> parts
) {
}
