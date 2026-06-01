package com.example.TestAPI.Controller;

import com.example.TestAPI.DTO.Job.AssignJobRequest;
import com.example.TestAPI.DTO.Job.CreateJobRequest;
import com.example.TestAPI.DTO.Job.JobResponse;
import com.example.TestAPI.DTO.Job.UpdateJobRequest;
import com.example.TestAPI.Mapper.JobOfferMapper;
import com.example.TestAPI.Model.JobOffer;
import com.example.TestAPI.Model.User;
import com.example.TestAPI.Model.Enum.JobStatus;
import com.example.TestAPI.Service.JobOffer.JobService;
import com.example.TestAPI.Service.Storage.FileStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final JobOfferMapper jobMapper;
    private final FileStorageService fileStorageService;

    /**
     * Créer une nouvelle annonce de job
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<JobResponse> createJob(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody CreateJobRequest request) {

        JobOffer job = jobService.createJob(currentUser, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(jobMapper.toDTO(job));
    }

    /**
     * Récupérer toutes les annonces disponibles (PENDING)
     */
    @GetMapping("/available")
    public ResponseEntity<List<JobResponse>> getAvailableJobs(
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false, defaultValue = "date_desc") String sort,
            @RequestParam(required = false) String location) {

        List<JobResponse> jobs = jobService.getAvailableJobs(categoryId, q, minPrice, maxPrice, sort, location)
                .stream()
                .map(jobMapper::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(jobs);
    }

    /**
     * Récupérer les annonces créées par l'utilisateur connecté
     */
    @GetMapping("/my-created")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<JobResponse>> getMyCreatedJobs(
            @AuthenticationPrincipal User currentUser) {

        List<JobResponse> jobs = jobService.getJobsCreatedByUser(currentUser)
                .stream()
                .map(jobMapper::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(jobs);
    }

    /**
     * Récupérer les annonces assignées à l'utilisateur connecté
     */
    @GetMapping("/my-assigned")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<JobResponse>> getMyAssignedJobs(
            @AuthenticationPrincipal User currentUser) {

        List<JobResponse> jobs = jobService.getJobsAssignedToUser(currentUser)
                .stream()
                .map(jobMapper::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(jobs);
    }

    /**
     * Récupérer une annonce par son ID
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<JobResponse> getJobById(@PathVariable UUID jobId) {
        JobOffer job = jobService.getJobById(jobId);
        return ResponseEntity.ok(jobMapper.toDTO(job));
    }

    /**
     * Modifier une annonce (seul le créateur peut modifier)
     */
    @PutMapping("/{jobId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<JobResponse> updateJob(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody UpdateJobRequest request) {

        JobOffer updatedJob = jobService.updateJob(jobId, currentUser, request);
        return ResponseEntity.ok(jobMapper.toDTO(updatedJob));
    }

    /**
     * Assigner un job à un worker
     */
    @PostMapping("/{jobId}/assign")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<JobResponse> assignJob(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody AssignJobRequest request) {

        JobOffer assignedJob = jobService.assignJob(jobId, currentUser, request);
        return ResponseEntity.ok(jobMapper.toDTO(assignedJob));
    }

    /**
     * Mettre à jour le statut d'un job
     */
    @PatchMapping("/{jobId}/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<JobResponse> updateJobStatus(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal User currentUser,
            @RequestParam JobStatus status) {

        JobOffer updatedJob = jobService.updateJobStatus(jobId, currentUser, status);
        return ResponseEntity.ok(jobMapper.toDTO(updatedJob));
    }

    /**
     * Supprimer une annonce (seul le créateur peut supprimer)
     */
    @DeleteMapping("/{jobId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteJob(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal User currentUser) {

        jobService.deleteJob(jobId, currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * Expirer une annonce (seul le créateur peut expirer)
     */
    @PostMapping("/{jobId}/expire")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<JobResponse> expireJob(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal User currentUser) {

        JobOffer expiredJob = jobService.expireJob(jobId, currentUser);
        return ResponseEntity.ok(jobMapper.toDTO(expiredJob));
    }

    /**
     * Uploader une image pour un job
     */
    @PostMapping(value = "/{jobId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<JobResponse> uploadImage(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal User currentUser,
            @RequestParam("file") MultipartFile file) {

        String imageUrl = fileStorageService.store(file, "jobs");
        JobOffer job = jobService.addImages(jobId, currentUser, List.of(imageUrl));
        return ResponseEntity.ok(jobMapper.toDTO(job));
    }

    /**
     * Supprimer une image d'un job
     */
    @DeleteMapping("/{jobId}/images")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<JobResponse> removeImage(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal User currentUser,
            @RequestParam("imageUrl") String imageUrl) {

        JobOffer job = jobService.removeImage(jobId, currentUser, imageUrl);
        return ResponseEntity.ok(jobMapper.toDTO(job));
    }
}