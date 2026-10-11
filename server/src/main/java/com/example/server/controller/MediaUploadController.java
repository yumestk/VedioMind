package com.example.server.controller;

import com.example.server.dto.CompleteMediaUploadRequest;
import com.example.server.dto.InitiateMediaUploadRequest;
import com.example.server.dto.InitiateMediaUploadResponse;
import com.example.server.dto.MediaListItemResponse;
import com.example.server.dto.MediaUploadSessionResponse;
import com.example.server.dto.UploadPartUrlResponse;
import com.example.server.service.MediaUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/media/uploads")
public class MediaUploadController {

    private final MediaUploadService uploadService;

    public MediaUploadController(MediaUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping
    public ResponseEntity<InitiateMediaUploadResponse> initiate(
            @RequestBody InitiateMediaUploadRequest request,
            @RequestParam Long userId
    ) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(uploadService.initiate(request, userId));
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception);
        }
    }

    @GetMapping("/{sessionId}")
    public MediaUploadSessionResponse get(
            @PathVariable String sessionId,
            @RequestParam Long userId
    ) {
        try {
            return uploadService.get(sessionId, userId);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception);
        }
    }

    @PostMapping("/{sessionId}/parts/{partNumber}/url")
    public UploadPartUrlResponse createPartUrl(
            @PathVariable String sessionId,
            @PathVariable int partNumber,
            @RequestParam Long userId
    ) {
        try {
            return uploadService.createPartUrl(sessionId, partNumber, userId);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception);
        } catch (IllegalStateException exception) {
            throw conflict(exception);
        }
    }

    @PostMapping("/{sessionId}/complete")
    public MediaListItemResponse complete(
            @PathVariable String sessionId,
            @RequestBody CompleteMediaUploadRequest request,
            @RequestParam Long userId
    ) {
        try {
            return uploadService.complete(sessionId, request, userId);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception);
        } catch (IllegalStateException exception) {
            throw conflict(exception);
        }
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> abort(
            @PathVariable String sessionId,
            @RequestParam Long userId
    ) {
        try {
            uploadService.abort(sessionId, userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception);
        } catch (IllegalStateException exception) {
            throw conflict(exception);
        }
    }

    private ResponseStatusException badRequest(RuntimeException exception) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }

    private ResponseStatusException conflict(RuntimeException exception) {
        return new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
    }
}
