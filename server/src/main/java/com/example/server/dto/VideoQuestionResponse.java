package com.example.server.dto;

import java.util.List;

public record VideoQuestionResponse(
        String answer,
        List<VideoQuestionCitationResponse> citations
) {
    public VideoQuestionResponse {
        citations = List.copyOf(citations);
    }
}
