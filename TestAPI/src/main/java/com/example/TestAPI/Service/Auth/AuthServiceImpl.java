package com.example.TestAPI.Service.Auth;

import com.example.TestAPI.DTO.Login.LoginRequest;
import com.example.TestAPI.DTO.Login.LoginResponse;
import com.example.TestAPI.DTO.Login.RegisterRequest;
import com.example.TestAPI.DTO.Me.MeResponse;
import com.example.TestAPI.DTO.Token.RefreshRequest;
import com.example.TestAPI.DTO.User.UserResponse;
import com.example.TestAPI.DTO.User.UserStatsResponse;
import com.example.TestAPI.Mapper.UserMapper;
import com.example.TestAPI.Model.Enum.ActionType;
import com.example.TestAPI.Model.Enum.JobStatus;
import com.example.TestAPI.Model.Enum.KycStatus;
import com.example.TestAPI.Model.Enum.Role;
import com.example.TestAPI.Model.RefreshToken;
import com.example.TestAPI.Model.User;
import com.example.TestAPI.Repository.ApplicationRepository;
import com.example.TestAPI.Repository.JobOfferRepository;
import com.example.TestAPI.Repository.UserRepository;
import com.example.TestAPI.Security.JwtService;
import com.example.TestAPI.Service.Audit.AuditService;
import com.example.TestAPI.Service.Otp.OtpService;
import com.example.TestAPI.Service.RefreshToken.RefreshTokenService;
import com.example.TestAPI.Service.RateLimiter.ForgotPasswordRateLimiter;
import com.example.TestAPI.Service.Storage.FileStorageService;
import com.example.TestAPI.exception.BusinessException;
import com.example.TestAPI.exception.ErrorCode;
import com.example.TestAPI.exception.UserAlreadyVerifiedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepo;
    private final JobOfferRepository jobOfferRepository;
    private final ApplicationRepository applicationRepository;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final OtpService otpService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final FileStorageService fileStorageService;
    private final ForgotPasswordRateLimiter rateLimiter;

    public AuthServiceImpl(UserRepository userRepo, JobOfferRepository jobOfferRepository, ApplicationRepository applicationRepository, JwtService jwtService, UserMapper userMapper, OtpService otpService, RefreshTokenService refreshTokenService, AuditService auditService, FileStorageService fileStorageService, ForgotPasswordRateLimiter rateLimiter) {
        this.userRepo = userRepo;
        this.jobOfferRepository = jobOfferRepository;
        this.applicationRepository = applicationRepository;
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.otpService = otpService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.fileStorageService = fileStorageService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public UserResponse register(RegisterRequest request) {

        // Vérification des champs obligatoires
        if (request.firstName() == null || request.firstName().isBlank()) {
            throw new IllegalArgumentException("Le prénom est obligatoire");
        }
        if (request.lastName() == null || request.lastName().isBlank()) {
            throw new IllegalArgumentException("Le nom est obligatoire");
        }
        if (request.username() == null || request.username().isBlank()) {
            throw new IllegalArgumentException("Le nom d'utilisateur est obligatoire");
        }
        if (request.email() == null || request.email().isBlank()) {
            throw new IllegalArgumentException("L'email est obligatoire");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("Le mot de passe est obligatoire");
        }

        // Vérification des doublons
        if (userRepo.existsByUsername(request.username())) {
            throw new BusinessException("Nom d'utilisateur déjà utilisé", ErrorCode.CONFLICT);
        }
        if (userRepo.existsByEmail(request.email())) {
            throw new BusinessException("Email déjà utilisé", ErrorCode.CONFLICT);
        }

        // Création de l'utilisateur
        User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .username(request.username())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .role(Role.USER)
                .verified(false)
                .kycStatus(KycStatus.PENDING)
                .build();

        User saved = userRepo.save(user);

        auditService.log(saved, ActionType.REGISTER, "User: " + saved.getId());

        // Génération OTP après création
        otpService.generateAndSendOtp(saved);

        return userMapper.toDTO(saved);
    }

    @Override
    @Transactional
    public LoginResponse verifyOtp(String email, String code) {
        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> new BusinessException("Utilisateur non trouvé", ErrorCode.NOT_FOUND));

        boolean success = otpService.verifyOtp(email, code);
        if (!success) {
            throw new BusinessException("OTP invalide ou expiré", ErrorCode.BAD_REQUEST);
        }

        String accesstoken = jwtService.generateToken(user.getUsername());
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);
        return new LoginResponse(accesstoken, refreshToken.getToken(), true);
    }

    @Transactional
    @Override
    public LoginResponse login(LoginRequest request)  {
        User user = userRepo.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException("Email ou mot de passe incorrect", ErrorCode.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException("Email ou mot de passe incorrect", ErrorCode.UNAUTHORIZED);
        }

        if (!user.isVerified()) {
            auditService.log(user, ActionType.LOGIN, "User non vérifié: " + user.getId());
            String acesstoken = jwtService.generateToken(user.getUsername());
            RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);
            return new LoginResponse(acesstoken, refreshToken.getToken(), false);
        }

        auditService.log(user, ActionType.LOGIN, "User: " + user.getId());
        String acesstoken = jwtService.generateToken(user.getUsername());
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);
        return new LoginResponse(acesstoken, refreshToken.getToken(), true);
    }

    public void resendOtp(String email) {
        userRepo.findByEmail(email).ifPresent(user -> {
            if (!user.isVerified()) {
                otpService.generateAndSendOtp(user);
            }
        });
    }

    @Override
    public LoginResponse refreshToken(RefreshRequest request) {
        RefreshToken refreshToken = refreshTokenService.validateRefreshToken(request.refreshToken());

        String newAccessToken = jwtService.generateToken(refreshToken.getUser().getUsername());
        return new LoginResponse(newAccessToken, refreshToken.getToken(), refreshToken.getUser().isVerified());
    }

    @Override
    @Transactional
    public MeResponse updateProfilePhoto(User user, MultipartFile file) {
        String photoUrl = fileStorageService.store(file, "profiles");
        user.setPhotoProfil(photoUrl);
        userRepo.save(user);
        auditService.log(user, ActionType.UPDATE_PROFILE, "Photo de profil mise à jour");
        return getCurrentUser(user);
    }

    @Override
    public MeResponse getCurrentUser(User user) {
        return new MeResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.isVerified(),
                user.getKycStatus(),
                user.getPhotoProfil(),
                user.getAverageRating(),
                user.getTotalRatings()
        );
    }

    @Override
    public UserStatsResponse getUserStats(User user) {
        long totalJobsCreated = jobOfferRepository.countByCreator(user);
        long totalJobsInProgress = jobOfferRepository.countByWorkerAndStatus(user, JobStatus.IN_PROGRESS);
        long totalJobsCompleted = jobOfferRepository.countByWorkerAndStatus(user, JobStatus.DONE);
        long totalApplicationsReceived = applicationRepository.countByJob_Creator(user);
        long totalApplicationsSent = applicationRepository.countByWorker(user);

        return new UserStatsResponse(
                totalJobsCreated,
                totalJobsInProgress,
                totalJobsCompleted,
                user.getAverageRating(),
                user.getTotalRatings() != null ? user.getTotalRatings() : 0,
                totalApplicationsReceived,
                totalApplicationsSent
        );
    }

    @Override
    @Transactional
    public void forgotPassword(String email) {
        rateLimiter.check(email);

        User user = userRepo.findByEmail(email).orElse(null);
        if (user == null) {
            return;
        }

        otpService.generateAndSendOtp(user);
    }

    @Override
    @Transactional
    public void resetPassword(String email, String otp, String newPassword) {
        boolean valid = otpService.validateOtpOnly(email, otp);
        if (!valid) {
            throw new BusinessException("Code invalide ou expiré", ErrorCode.BAD_REQUEST);
        }

        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> new BusinessException("Utilisateur non trouvé", ErrorCode.NOT_FOUND));

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepo.save(user);

        rateLimiter.clear(email);

        auditService.log(user, ActionType.PASSWORD_RESET, "User: " + user.getId());
    }

}
