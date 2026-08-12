package com.gradion.studio;

import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/steps/{step}")
class PipelineController {
    private final CurrentUser currentUser;
    private final PipelineService pipelineService;

    PipelineController(CurrentUser currentUser, PipelineService pipelineService) {
        this.currentUser = currentUser;
        this.pipelineService = pipelineService;
    }

    @PostMapping("/run")
    ResponseEntity<?> run(@PathVariable String projectId, @PathVariable String step, HttpServletRequest request) {
        Optional<CurrentUser.User> user = currentUser.find(request);
        if (user.isEmpty()) return unauthorized();
        PipelineService.StepKey requested = PipelineService.StepKey.parse(step);
        if (requested == null) return badRequest("Unknown pipeline step.");
        return response(pipelineService.run(user.get().id(), projectId, requested));
    }

    @PostMapping("/recover")
    ResponseEntity<?> recover(@PathVariable String projectId, @PathVariable String step, HttpServletRequest request) {
        Optional<CurrentUser.User> user = currentUser.find(request);
        if (user.isEmpty()) return unauthorized();
        PipelineService.StepKey requested = PipelineService.StepKey.parse(step);
        if (requested == null) return badRequest("Unknown pipeline step.");
        return response(pipelineService.recover(user.get().id(), projectId, requested));
    }

    private ResponseEntity<?> response(PipelineService.Result result) {
        if (result.notFound()) return ResponseEntity.notFound().build();
        if (result.message() != null) return ResponseEntity.status(409).body(Map.of("message", result.message()));
        return ResponseEntity.ok(result.pipeline());
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("message", "No active session."));
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
