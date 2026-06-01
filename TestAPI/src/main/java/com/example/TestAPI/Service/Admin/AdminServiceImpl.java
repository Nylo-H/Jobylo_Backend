package com.example.TestAPI.Service.Admin;

import com.example.TestAPI.DTO.Admin.AdminStatsResponse;
import com.example.TestAPI.DTO.Admin.CreateCategoryRequest;
import com.example.TestAPI.DTO.Admin.FinanceStatsResponse;
import com.example.TestAPI.DTO.Admin.MonthlyRevenue;
import com.example.TestAPI.DTO.Admin.UpdateKycRequest;
import com.example.TestAPI.DTO.Admin.UpdateUserRoleRequest;
import com.example.TestAPI.DTO.Audit.ActionLogResponse;
import com.example.TestAPI.DTO.Category.CategoryResponse;
import com.example.TestAPI.DTO.Job.JobResponse;
import com.example.TestAPI.DTO.Payment.PaymentResponse;
import com.example.TestAPI.DTO.User.UserResponse;
import com.example.TestAPI.Mapper.JobOfferMapper;
import com.example.TestAPI.Mapper.UserMapper;
import com.example.TestAPI.Model.*;
import com.example.TestAPI.Model.Enum.*;
import com.example.TestAPI.Repository.*;
import com.example.TestAPI.exception.BusinessException;
import com.example.TestAPI.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminServiceImpl implements AdminService {

    private final UserRepository userRepository;
    private final JobOfferRepository jobRepository;
    private final TransactionRepository transactionRepository;
    private final ApplicationRepository applicationRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final ActionLogRepository actionLogRepository;
    private final JobCategoryRepository categoryRepository;
    private final UserMapper userMapper;
    private final JobOfferMapper jobOfferMapper;

    @Override
    @Transactional(readOnly = true)
    public AdminStatsResponse getStats() {
        return new AdminStatsResponse(
                userRepository.count(),
                userRepository.countByVerified(true),
                userRepository.countByKycStatus(KycStatus.PENDING),
                userRepository.countByKycStatus(KycStatus.VERIFIED),
                userRepository.countByKycStatus(KycStatus.REJECTED),
                jobRepository.countByStatus(JobStatus.PENDING),
                jobRepository.countByStatus(JobStatus.IN_PROGRESS),
                jobRepository.countByStatus(JobStatus.DONE),
                jobRepository.countByStatus(JobStatus.EXPIRED),
                transactionRepository.countByStatus(PaymentStatus.HELD),
                transactionRepository.countByStatus(PaymentStatus.COMPLETED),
                transactionRepository.countByStatus(PaymentStatus.CANCELLED),
                applicationRepository.count(),
                applicationRepository.countByStatus(ApplicationStatus.PENDING),
                actionLogRepository.count()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public FinanceStatsResponse getFinanceStats() {
        BigDecimal totalVolume    = transactionRepository.sumAmountByStatus(PaymentStatus.COMPLETED);
        BigDecimal heldAmount     = transactionRepository.sumAmountByStatus(PaymentStatus.HELD);
        BigDecimal totalCommission = transactionRepository.sumCommissionByStatus(PaymentStatus.COMPLETED);
        BigDecimal totalPaid      = transactionRepository.sumNetByStatus(PaymentStatus.COMPLETED);
        long completedCount       = transactionRepository.countByStatus(PaymentStatus.COMPLETED);
        long heldCount            = transactionRepository.countByStatus(PaymentStatus.HELD);
        long cancelledCount       = transactionRepository.countByStatus(PaymentStatus.CANCELLED);

        BigDecimal avg = completedCount > 0
                ? totalVolume.divide(BigDecimal.valueOf(completedCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Date since = Date.from(LocalDateTime.now().minusMonths(12)
                .atZone(ZoneId.systemDefault()).toInstant());
        List<MonthlyRevenue> monthly = transactionRepository.sumRevenueByMonth(since);

        return new FinanceStatsResponse(
                totalVolume, heldAmount, totalCommission, totalPaid,
                transactionRepository.count(),
                completedCount, heldCount, cancelledCount,
                avg, monthly
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(userMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("Utilisateur non trouvé", ErrorCode.NOT_FOUND));
        return userMapper.toDTO(user);
    }

    @Override
    public UserResponse updateUserRole(UUID userId, UpdateUserRoleRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("Utilisateur non trouvé", ErrorCode.NOT_FOUND));
        user.setRole(request.role());
        user = userRepository.save(user);
        return userMapper.toDTO(user);
    }

    @Override
    public UserResponse updateUserKyc(User admin, UUID userId, UpdateKycRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("Utilisateur non trouvé", ErrorCode.NOT_FOUND));

        KycDocument doc = kycDocumentRepository.findByUserId(userId).orElse(null);
        if (doc != null) {
            doc.setStatus(request.status());
            doc.setVerifiedBy(admin);
            if (request.rejectionReason() != null) {
                doc.setRejectionReason(request.rejectionReason());
            }
            kycDocumentRepository.save(doc);
        }

        user.setKycStatus(request.status());
        user = userRepository.save(user);
        return userMapper.toDTO(user);
    }

    @Override
    public void deleteUser(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException("Utilisateur non trouvé", ErrorCode.NOT_FOUND);
        }
        userRepository.deleteById(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobResponse> getAllJobs() {
        return jobRepository.findAll().stream()
                .map(jobOfferMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentResponse> getAllTransactions() {
        return transactionRepository.findAll().stream()
                .map(this::toPaymentResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActionLogResponse> getAllAuditLogs() {
        return actionLogRepository.findAllByOrderByTimestampDesc().stream()
                .map(this::toActionLogResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll().stream()
                .map(this::toCategoryResponse)
                .collect(Collectors.toList());
    }

    @Override
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        if (categoryRepository.existsByName(request.name())) {
            throw new BusinessException("Une catégorie avec ce nom existe déjà", ErrorCode.CONFLICT);
        }

        JobCategory parent = null;
        if (request.parentId() != null) {
            parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new BusinessException("Catégorie parente non trouvée", ErrorCode.NOT_FOUND));
        }

        JobCategory category = JobCategory.builder()
                .name(request.name())
                .description(request.description())
                .icon(request.icon())
                .parent(parent)
                .displayOrder(request.displayOrder())
                .build();

        category = categoryRepository.save(category);
        return toCategoryResponse(category);
    }

    @Override
    public CategoryResponse updateCategory(UUID categoryId, CreateCategoryRequest request) {
        JobCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException("Catégorie non trouvée", ErrorCode.NOT_FOUND));

        category.setName(request.name());
        category.setDescription(request.description());
        category.setIcon(request.icon());
        category.setDisplayOrder(request.displayOrder());

        if (request.parentId() != null) {
            JobCategory parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new BusinessException("Catégorie parente non trouvée", ErrorCode.NOT_FOUND));
            category.setParent(parent);
        } else {
            category.setParent(null);
        }

        category = categoryRepository.save(category);
        return toCategoryResponse(category);
    }

    @Override
    public void deleteCategory(UUID categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new BusinessException("Catégorie non trouvée", ErrorCode.NOT_FOUND);
        }
        categoryRepository.deleteById(categoryId);
    }

    private PaymentResponse toPaymentResponse(Transaction t) {
        return new PaymentResponse(
                t.getId(),
                t.getJob().getId(),
                t.getJob().getTitle(),
                t.getBuyer().getId(),
                t.getBuyer().getUsername(),
                t.getSeller().getId(),
                t.getSeller().getUsername(),
                t.getAmount(),
                t.getCommissionPercentage(),
                t.getCommissionAmount(),
                t.getNetAmount(),
                t.getStatus().name(),
                t.getPaymentMethod(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }

    private ActionLogResponse toActionLogResponse(ActionLog log) {
        return new ActionLogResponse(
                log.getId(),
                log.getUser().getId(),
                log.getUser().getUsername(),
                log.getAction(),
                log.getDetails(),
                log.getTimestamp()
        );
    }

    private CategoryResponse toCategoryResponse(JobCategory category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getIcon(),
                category.getParent() != null ? category.getParent().getId() : null,
                category.getDisplayOrder()
        );
    }
}
