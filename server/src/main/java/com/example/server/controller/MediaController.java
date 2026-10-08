package com.example.server.controller;

import com.example.server.dto.MediaDetailResponse;
import com.example.server.dto.MediaListItemResponse;
import com.example.server.dto.MediaTranscriptResponse;
import com.example.server.dto.VideoQuestionRequest;
import com.example.server.dto.VideoQuestionResponse;
import com.example.server.service.MediaService;
import com.example.server.service.VideoQuestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/media")
public class MediaController {

    private final MediaService mediaService;
    private final VideoQuestionService videoQuestionService;

    public MediaController(MediaService mediaService, VideoQuestionService videoQuestionService) {
        this.mediaService = mediaService;
        this.videoQuestionService = videoQuestionService;
    }

    @PostMapping("/upload")
    public ResponseEntity<MediaListItemResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam Long userId
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(mediaService.upload(file, userId));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
        }
    }

    @PostMapping("/upload-url")
    public ResponseEntity<MediaListItemResponse> uploadUrl(
            @RequestParam String url,
            @RequestParam Long userId
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(mediaService.importFromUrl(url, userId));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
        }
    }

    @GetMapping
    public List<MediaListItemResponse> list(@RequestParam Long userId) {
        return mediaService.list(userId);
    }

    @GetMapping("/{mediaId}")
    public MediaDetailResponse getDetail(
            @PathVariable Long mediaId,
            @RequestParam Long userId
    ) {
        try {
            return mediaService.getDetail(mediaId, userId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long mediaId,
            @RequestParam Long userId
    ) {
        try {
            mediaService.delete(mediaId, userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping("/{mediaId}/transcript")
    public MediaTranscriptResponse getTranscript(
            @PathVariable Long mediaId,
            @RequestParam Long userId
    ) {
        try {
            return mediaService.getTranscript(mediaId, userId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @PostMapping("/{mediaId}/questions")
    public VideoQuestionResponse askQuestion(
            @PathVariable Long mediaId,
            @RequestParam Long userId,
            @RequestBody VideoQuestionRequest request
    ) {
        MediaTranscriptResponse transcript;
        try {
            transcript = mediaService.getTranscript(mediaId, userId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
        if (transcript.segments().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analyze the video before asking questions");
        }
        try {
            return videoQuestionService.answer(request.question(), transcript.segments());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
        }
    }
}
