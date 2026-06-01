package com.example.TestAPI.Service.Application;

import com.example.TestAPI.DTO.Application.ApplicationResponse;
import com.example.TestAPI.Model.Application;
import com.example.TestAPI.Model.Enum.ApplicationStatus;
import com.example.TestAPI.Model.Enum.JobStatus;
import com.example.TestAPI.Model.JobOffer;
import com.example.TestAPI.Model.User;
import com.example.TestAPI.Repository.ApplicationRepository;
import com.example.TestAPI.Repository.JobOfferRepository;
import com.example.TestAPI.Repository.UserRepository;
import com.example.TestAPI.Service.Audit.KycGuard;
import com.example.TestAPI.exception.BusinessException;
import com.example.TestAPI.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final JobOfferRepository jobRepository;
    private final UserRepository userRepository;
    private final KycGuard kycGuard;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public ApplicationResponse apply(User worker, UUID jobId, String coverLetter) {
        kycGuard.requireVerified(worker);

        JobOffer job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException("Offre non trouvée", ErrorCode.NOT_FOUND));

        if (job.getStatus() == JobStatus.EXPIRED) {
            throw new BusinessException("Cette offre a expiré", ErrorCode.BAD_REQUEST);
        }

        if (job.getStatus() != JobStatus.PENDING) {
            throw new BusinessException("Cette offre n'est plus disponible", ErrorCode.BAD_REQUEST);
        }

        if (job.getApplicationDeadline() != null && job.getApplicationDeadline().before(new Date())) {
            throw new BusinessException("La date limite de candidature est dépassée", ErrorCode.BAD_REQUEST);
        }

        if (job.getCreator().getId().equals(worker.getId())) {
            throw new BusinessException("Vous ne pouvez pas postuler à votre propre offre", ErrorCode.BAD_REQUEST);
        }

        if (applicationRepository.existsByJobAndWorker(job, worker)) {
            throw new BusinessException("Vous avez déjà postulé à cette offre", ErrorCode.CONFLICT);
        }

        Application application = Application.builder()
                .job(job)
                .worker(worker)
                .status(ApplicationStatus.PENDING)
                .coverLetter(coverLetter)
                .createdAt(new Date())
                .build();

        application = applicationRepository.save(application);

        messagingTemplate.convertAndSend("/topic/notifications/" + job.getCreator().getId(), Map.of(
                "type", "NEW_APPLICATION",
                "jobId", jobId.toString(),
                "jobTitle", job.getTitle(),
                "applicantId", worker.getId().toString(),
                "applicantUsername", worker.getUsername(),
                "coverLetter", coverLetter != null ? coverLetter : ""
        ));

        return toResponse(application);
    }

    @Override
    public List<ApplicationResponse> getApplicants(User currentUser, UUID jobId) {
        JobOffer job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException("Offre non trouvée", ErrorCode.NOT_FOUND));

        if (!job.getCreator().getId().equals(currentUser.getId())) {
            throw new BusinessException("Seul le créateur peut voir les candidatures", ErrorCode.FORBIDDEN);
        }

        return applicationRepository.findByJobOrderByCreatedAtDesc(job)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public void rejectApplicant(User currentUser, UUID jobId, UUID workerId) {
        JobOffer job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException("Offre non trouvée", ErrorCode.NOT_FOUND));

        if (!job.getCreator().getId().equals(currentUser.getId())) {
            throw new BusinessException("Seul le créateur peut rejeter une candidature", ErrorCode.FORBIDDEN);
        }

        if (job.getStatus() != JobStatus.PENDING) {
            throw new BusinessException("Impossible de rejeter une candidature sur une offre déjà attribuée", ErrorCode.BAD_REQUEST);
        }

        User worker = userRepository.findById(workerId)
                .orElseThrow(() -> new BusinessException("Utilisateur non trouvé", ErrorCode.NOT_FOUND));

        Application application = applicationRepository.findByJobAndWorker(job, worker)
                .orElseThrow(() -> new BusinessException("Candidature non trouvée", ErrorCode.NOT_FOUND));

        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new BusinessException("Cette candidature a déjà été traitée", ErrorCode.BAD_REQUEST);
        }

        application.setStatus(ApplicationStatus.REJECTED);
        applicationRepository.save(application);

        messagingTemplate.convertAndSend("/topic/notifications/" + worker.getId(), Map.of(
                "type", "APPLICATION_REJECTED",
                "jobId", jobId.toString(),
                "jobTitle", job.getTitle(),
                "message", "Votre candidature a été refusée"
        ));
    }

    @Override
    public List<ApplicationResponse> getMyApplications(User worker) {
        return applicationRepository.findByWorkerOrderByCreatedAtDesc(worker)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public long countByJob(UUID jobId) {
        JobOffer job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException("Offre non trouvée", ErrorCode.NOT_FOUND));
        return applicationRepository.countByJobAndStatus(job, ApplicationStatus.PENDING);
    }

    private ApplicationResponse toResponse(Application app) {
        return new ApplicationResponse(
                app.getId(),
                app.getJob().getId(),
                app.getJob().getTitle(),
                app.getWorker().getId(),
                app.getWorker().getUsername(),
                app.getCoverLetter(),
                app.getStatus(),
                app.getCreatedAt()
        );
    }
}
