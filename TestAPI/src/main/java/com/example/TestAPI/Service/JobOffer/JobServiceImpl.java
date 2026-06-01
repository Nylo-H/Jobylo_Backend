package com.example.TestAPI.Service.JobOffer;

import com.example.TestAPI.DTO.Job.AssignJobRequest;
import com.example.TestAPI.DTO.Job.CreateJobRequest;
import com.example.TestAPI.DTO.Job.UpdateJobRequest;
import com.example.TestAPI.Model.Application;
import com.example.TestAPI.Model.Enum.ActionType;
import com.example.TestAPI.Model.Enum.ApplicationStatus;
import com.example.TestAPI.Model.Enum.JobStatus;
import com.example.TestAPI.Model.JobCategory;
import com.example.TestAPI.Model.JobOffer;
import com.example.TestAPI.Model.User;
import com.example.TestAPI.Repository.ApplicationRepository;
import com.example.TestAPI.Repository.JobCategoryRepository;
import com.example.TestAPI.Repository.JobOfferRepository;
import com.example.TestAPI.Repository.UserRepository;
import com.example.TestAPI.Service.Audit.AuditService;
import com.example.TestAPI.Service.Audit.KycGuard;
import com.example.TestAPI.exception.BusinessException;
import com.example.TestAPI.exception.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class JobServiceImpl implements JobService{

    private final JobOfferRepository jobRepository;
    private final UserRepository userRepository;
    private final JobCategoryRepository categoryRepository;
    private final ApplicationRepository applicationRepository;
    private final KycGuard kycGuard;
    private final AuditService auditService;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public JobOffer createJob(User creator, CreateJobRequest request) {
        kycGuard.requireVerified(creator);

        JobCategory category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new BusinessException("Catégorie non trouvée", ErrorCode.NOT_FOUND));
        }

        JobOffer job = JobOffer.builder()
                .title(request.title())
                .description(request.description())
                .location(request.location())
                .price(request.price())
                .creator(creator)
                .status(JobStatus.PENDING)
                .createdAt(new Date())
                .updatedAt(new Date())
                .images(request.images() != null ? new java.util.ArrayList<>(request.images()) : new java.util.ArrayList<>())
                .category(category)
                .applicationDeadline(request.applicationDeadline())
                .build();

        job = jobRepository.save(job);
        auditService.log(creator, ActionType.CREATE_JOB, "Job: " + job.getId());
        return job;
    }

    @Override
    public JobOffer updateJob(UUID jobId, User currentUser, UpdateJobRequest request) {
        kycGuard.requireVerified(currentUser);

        JobOffer job = getJobById(jobId);

        if (!job.getCreator().getId().equals(currentUser.getId())) {
            throw new BusinessException("Vous n'êtes pas autorisé à modifier cette annonce", ErrorCode.FORBIDDEN);
        }

        if (job.getStatus() != JobStatus.PENDING) {
            throw new BusinessException("Impossible de modifier une annonce déjà attribuée ou en cours", ErrorCode.BAD_REQUEST);
        }

        if (request.title() != null) job.setTitle(request.title());
        if (request.description() != null) job.setDescription(request.description());
        if (request.location() != null) job.setLocation(request.location());
        if (request.price() != null) job.setPrice(request.price());
        if (request.images() != null) job.setImages(new java.util.ArrayList<>(request.images()));
        if (request.categoryId() != null) {
            JobCategory category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new BusinessException("Catégorie non trouvée", ErrorCode.NOT_FOUND));
            job.setCategory(category);
        }

        if (request.applicationDeadline() != null) {
            job.setApplicationDeadline(request.applicationDeadline());
        }

        job.setUpdatedAt(new Date());
        job = jobRepository.save(job);
        auditService.log(currentUser, ActionType.UPDATE_JOB, "Job: " + jobId);
        return job;
    }

    @Override
    public JobOffer assignJob(UUID jobId, User currentUser, AssignJobRequest request) {
        kycGuard.requireVerified(currentUser);

        JobOffer job = getJobById(jobId);

        if (!job.getCreator().getId().equals(currentUser.getId())) {
            throw new BusinessException("Vous n'êtes pas autorisé à attribuer cette annonce", ErrorCode.FORBIDDEN);
        }

        if (job.getStatus() == JobStatus.EXPIRED) {
            throw new BusinessException("Cette annonce a expiré", ErrorCode.BAD_REQUEST);
        }
        if (job.getStatus() != JobStatus.PENDING) {
            throw new BusinessException("Cette annonce n'est plus disponible", ErrorCode.BAD_REQUEST);
        }

        User worker = userRepository.findById(request.workerId())
                .orElseThrow(() -> new BusinessException("Utilisateur non trouvé", ErrorCode.NOT_FOUND));

        if (worker.getId().equals(currentUser.getId())) {
            throw new BusinessException("Vous ne pouvez pas attribuer une annonce à vous-même", ErrorCode.BAD_REQUEST);
        }

        kycGuard.requireVerified(worker);

        applicationRepository.findByJobAndWorker(job, worker)
                .ifPresent(app -> {
                    app.setStatus(ApplicationStatus.ACCEPTED);
                    applicationRepository.save(app);
                });

        List<Application> others = applicationRepository.findByJobAndStatus(job, ApplicationStatus.PENDING);
        for (Application app : others) {
            app.setStatus(ApplicationStatus.REJECTED);
            applicationRepository.save(app);
            messagingTemplate.convertAndSend("/topic/notifications/" + app.getWorker().getId(), Map.of(
                    "type", "APPLICATION_REJECTED",
                    "jobId", jobId.toString(),
                    "jobTitle", job.getTitle(),
                    "message", "L'offre a été attribuée à un autre candidat"
            ));
        }

        job.setWorker(worker);
        job.setStatus(JobStatus.IN_PROGRESS);
        job.setUpdatedAt(new Date());

        job = jobRepository.save(job);
        auditService.log(currentUser, ActionType.ASSIGN_JOB, "Job: " + jobId + " -> Worker: " + worker.getId());
        return job;
    }

    @Override
    public JobOffer updateJobStatus(UUID jobId, User currentUser, JobStatus status) {
        JobOffer job = getJobById(jobId);

        boolean isCreator = job.getCreator().getId().equals(currentUser.getId());
        boolean isWorker = job.getWorker() != null && job.getWorker().getId().equals(currentUser.getId());

        if (!isCreator && !isWorker) {
            throw new BusinessException("Vous n'êtes pas autorisé à modifier le statut", ErrorCode.FORBIDDEN);
        }

        validateStatusTransition(job.getStatus(), status, isCreator, isWorker);

        job.setStatus(status);
        job.setUpdatedAt(new Date());

        if (status == JobStatus.DONE) {
            auditService.log(currentUser, ActionType.COMPLETE_JOB, "Job: " + jobId);
        }

        return jobRepository.save(job);
    }

    @Override
    public JobOffer getJobById(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException("Annonce non trouvée", ErrorCode.NOT_FOUND));
    }

    @Override
    public List<JobOffer> getJobsCreatedByUser(User user) {
        return jobRepository.findByCreatorOrderByCreatedAtDesc(user);
    }

    @Override
    public List<JobOffer> getJobsAssignedToUser(User user) {
        return jobRepository.findByWorkerOrderByCreatedAtDesc(user);
    }

    @Override
    public List<JobOffer> getAvailableJobs(String categoryId, String q, BigDecimal minPrice, BigDecimal maxPrice, String sort, String location) {
        Specification<JobOffer> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), JobStatus.PENDING));
            predicates.add(cb.or(
                    cb.isNull(root.get("applicationDeadline")),
                    cb.greaterThan(root.get("applicationDeadline"), new Date())
            ));

            if (categoryId != null && !categoryId.isBlank()) {
                predicates.add(cb.equal(root.get("category").get("id"), UUID.fromString(categoryId)));
            }

            if (q != null && !q.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%" + q.toLowerCase() + "%"));
            }

            if (minPrice != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), minPrice));
            }

            if (maxPrice != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("price"), maxPrice));
            }

            if (location != null && !location.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("location")), "%" + location.toLowerCase() + "%"));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Sort sorting = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort != null) {
            switch (sort) {
                case "date_asc" -> sorting = Sort.by(Sort.Direction.ASC, "createdAt");
                case "price_asc" -> sorting = Sort.by(Sort.Direction.ASC, "price");
                case "price_desc" -> sorting = Sort.by(Sort.Direction.DESC, "price");
            }
        }

        return jobRepository.findAll(spec, sorting);
    }

    @Override
    public void deleteJob(UUID jobId, User currentUser) {
        kycGuard.requireVerified(currentUser);

        JobOffer job = getJobById(jobId);

        if (!job.getCreator().getId().equals(currentUser.getId())) {
            throw new BusinessException("Vous n'êtes pas autorisé à supprimer cette annonce", ErrorCode.FORBIDDEN);
        }

        if (job.getStatus() == JobStatus.IN_PROGRESS) {
            throw new BusinessException("Impossible de supprimer une annonce en cours", ErrorCode.BAD_REQUEST);
        }

        jobRepository.delete(job);
        auditService.log(currentUser, ActionType.DELETE_JOB, "Job: " + jobId);
    }

    @Override
    @Transactional
    public JobOffer addImages(UUID jobId, User currentUser, List<String> imageUrls) {
        kycGuard.requireVerified(currentUser);

        JobOffer job = getJobById(jobId);

        if (!job.getCreator().getId().equals(currentUser.getId())) {
            throw new BusinessException("Vous n'êtes pas autorisé à modifier cette annonce", ErrorCode.FORBIDDEN);
        }

        if (job.getImages() == null) {
            job.setImages(new java.util.ArrayList<>());
        }
        job.getImages().addAll(imageUrls);
        job.setUpdatedAt(new Date());
        job = jobRepository.save(job);
        auditService.log(currentUser, ActionType.UPDATE_JOB, "Images ajoutées au Job: " + jobId);
        return job;
    }

    @Override
    @Transactional
    public JobOffer removeImage(UUID jobId, User currentUser, String imageUrl) {
        kycGuard.requireVerified(currentUser);

        JobOffer job = getJobById(jobId);

        if (!job.getCreator().getId().equals(currentUser.getId())) {
            throw new BusinessException("Vous n'êtes pas autorisé à modifier cette annonce", ErrorCode.FORBIDDEN);
        }

        if (job.getImages() != null) {
            job.getImages().remove(imageUrl);
        }
        job.setUpdatedAt(new Date());
        job = jobRepository.save(job);
        auditService.log(currentUser, ActionType.UPDATE_JOB, "Image supprimée du Job: " + jobId);
        return job;
    }

    @Override
    public JobOffer expireJob(UUID jobId, User currentUser) {
        JobOffer job = getJobById(jobId);

        if (!job.getCreator().getId().equals(currentUser.getId())) {
            throw new BusinessException("Vous n'êtes pas autorisé à expirer cette annonce", ErrorCode.FORBIDDEN);
        }

        if (job.getStatus() != JobStatus.PENDING) {
            throw new BusinessException("Seules les annonces en attente peuvent être expirées", ErrorCode.BAD_REQUEST);
        }

        job.setStatus(JobStatus.EXPIRED);
        job.setUpdatedAt(new Date());
        job = jobRepository.save(job);
        auditService.log(currentUser, ActionType.EXPIRE_JOB, "Job: " + jobId);
        return job;
    }

    /**
     * Auto-expire les annonces PENDING dont la date limite est passée
     * ou qui sont inactives depuis plus de 90 jours.
     */
    @Scheduled(cron = "0 0 2 * * ?") // tous les jours à 2h du matin
    @Transactional
    public void autoExpireJobs() {
        Date now = new Date();
        LocalDateTime ninetyDaysAgo = LocalDateTime.now().minusDays(90);
        Date cutoff = Date.from(ninetyDaysAgo.atZone(ZoneId.systemDefault()).toInstant());

        List<JobOffer> expiredJobs = jobRepository.findByStatus(JobStatus.PENDING).stream()
                .filter(job -> {
                    // Expiré si applicationDeadline dépassée
                    if (job.getApplicationDeadline() != null && job.getApplicationDeadline().before(now)) {
                        return true;
                    }
                    // Expiré si inactif depuis 90 jours
                    return job.getCreatedAt().before(cutoff);
                })
                .toList();

        for (JobOffer job : expiredJobs) {
            job.setStatus(JobStatus.EXPIRED);
            job.setUpdatedAt(now);
            jobRepository.save(job);
            auditService.log(job.getCreator(), ActionType.EXPIRE_JOB, "Auto-expire Job: " + job.getId());
        }
    }

    private void validateStatusTransition(JobStatus currentStatus, JobStatus newStatus, boolean isCreator, boolean isWorker) {
        if (isCreator && newStatus == JobStatus.DONE && currentStatus == JobStatus.IN_PROGRESS) {
            return;
        }
        if (isWorker && newStatus == JobStatus.DONE && currentStatus == JobStatus.IN_PROGRESS) {
            return;
        }
        if (isCreator && newStatus == JobStatus.PENDING && currentStatus == JobStatus.PENDING) {
            return;
        }
        if (isCreator && newStatus == JobStatus.EXPIRED && currentStatus == JobStatus.PENDING) {
            return;
        }
        throw new BusinessException("Transition de statut invalide", ErrorCode.BAD_REQUEST);
    }
}
