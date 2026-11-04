package com.example.server.controller;

import com.example.server.security.CurrentUserId;
import com.example.server.service.CourseService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/courses")
public class CourseController {
    public record Create(String title, String description) {}
    public record AddSection(String title, int position) {}
    public record AddMaterial(Long mediaId, int position) {}
    public record Visibility(boolean published) {}
    private final CourseService courses;
    public CourseController(CourseService courses) { this.courses = courses; }

    @GetMapping public List<CourseService.Course> list(@CurrentUserId Long userId) { return courses.list(userId); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public CourseService.Detail create(@CurrentUserId Long userId, @RequestBody Create request) {
        return courses.create(userId, request.title(), request.description());
    }
    @GetMapping("/{id}") public CourseService.Detail detail(@PathVariable Long id, @CurrentUserId Long userId) {
        return courses.detail(id, userId);
    }
    @PostMapping("/{id}/sections")
    public CourseService.Detail section(@PathVariable Long id, @CurrentUserId Long userId, @RequestBody AddSection request) {
        return courses.addSection(id, userId, request.title(), request.position());
    }
    @PostMapping("/{id}/sections/{sectionId}/materials")
    public CourseService.Detail material(@PathVariable Long id, @PathVariable Long sectionId,
                                        @CurrentUserId Long userId, @RequestBody AddMaterial request) {
        return courses.addMaterial(id, sectionId, request.mediaId(), userId, request.position());
    }
    @DeleteMapping("/{id}/materials/{materialId}")
    public CourseService.Detail remove(@PathVariable Long id, @PathVariable Long materialId, @CurrentUserId Long userId) {
        return courses.removeMaterial(id, materialId, userId);
    }
    @PutMapping("/{id}/visibility")
    public CourseService.Detail visibility(@PathVariable Long id, @CurrentUserId Long userId, @RequestBody Visibility request) {
        return courses.publish(id, userId, request.published());
    }
    @ExceptionHandler(NoSuchElementException.class) public void missing(NoSuchElementException error) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage());
    }
    @ExceptionHandler(IllegalArgumentException.class) public void invalid(IllegalArgumentException error) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
    }
}
