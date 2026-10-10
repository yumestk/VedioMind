package com.example.server.controller;

import com.example.server.dto.MediaDetailResponse;
import com.example.server.dto.MediaChaptersResponse;
import com.example.server.dto.ExternalVideoImportRequest;
import com.example.server.dto.MediaListItemResponse;
import com.example.server.dto.MediaTranscriptResponse;
import com.example.server.service.MediaService;
import com.example.server.service.external.ExternalVideoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
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

    @PostMapping("/import-url")
    public ResponseEntity<MediaListItemResponse> importUrl(
            @RequestBody ExternalVideoImportRequest request,
            @RequestParam Long userId
    ) {
        try {
            String url = request == null ? null : request.url();
            return ResponseEntity.status(HttpStatus.CREATED).body(mediaService.importFromUrl(url, userId));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @ExceptionHandler(ExternalVideoException.class)
    public ProblemDetail handleExternalVideoException(ExternalVideoException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatus(), exception.getMessage());
        problem.setTitle("视频链接解析失败");
        problem.setProperty("code", exception.getCode());
        return problem;
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

    @GetMapping("/{mediaId}/chapters")
    public MediaChaptersResponse getChapters(
            @PathVariable Long mediaId,
            @RequestParam Long userId
    ) {
        try {
            return mediaService.getChapters(mediaId, userId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

}
