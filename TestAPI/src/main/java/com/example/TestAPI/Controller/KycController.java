package com.example.TestAPI.Controller;

import com.example.TestAPI.DTO.Kyc.KycDocumentResponse;
import com.example.TestAPI.DTO.Kyc.KycSubmissionRequest;
import com.example.TestAPI.Model.Enum.KycStatus;
import com.example.TestAPI.Model.User;
import com.example.TestAPI.Service.Kyc.KYCService;
import com.example.TestAPI.Service.Storage.FileStorageService;
import com.example.TestAPI.exception.BusinessException;
import com.example.TestAPI.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/kyc")
@RequiredArgsConstructor
public class KycController {

    private final KYCService kycService;
    private final FileStorageService fileStorageService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> uploadDocument(
            @AuthenticationPrincipal User currentUser,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") String documentType) {

        if (file.isEmpty()) {
            throw new BusinessException("Fichier vide", ErrorCode.BAD_REQUEST);
        }
        String fileUrl = fileStorageService.store(file, "kyc");

        KycSubmissionRequest request = new KycSubmissionRequest(fileUrl, documentType);
        KycDocumentResponse response = kycService.submitKYC(currentUser, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/submit")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> submitKYC(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody KycSubmissionRequest request) {

        if (currentUser.getKycStatus() == KycStatus.VERIFIED) {
            throw new BusinessException("Votre identité est déjà vérifiée", ErrorCode.CONFLICT);
        }

        KycDocumentResponse response = kycService.submitKYC(currentUser, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getMyKycStatus(@AuthenticationPrincipal User currentUser) {
        KycDocumentResponse doc = kycService.getMyKyc(currentUser);
        if (doc == null) {
            return ResponseEntity.ok(Map.of(
                    "status", currentUser.getKycStatus().name(),
                    "message", "Aucun document soumis"
            ));
        }
        return ResponseEntity.ok(doc);
    }

    @PostMapping("/{documentId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<KycDocumentResponse> approveKYC(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal User admin) {

        KycDocumentResponse response = kycService.approveKYC(documentId, admin);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{documentId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<KycDocumentResponse> rejectKYC(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal User admin,
            @RequestBody Map<String, String> body) {

        String reason = body.getOrDefault("reason", "Document non conforme");
        KycDocumentResponse response = kycService.rejectKYC(documentId, admin, reason);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<KycDocumentResponse>> getAllKYCs(
            @RequestParam(required = false) String status) {
        if (status != null) {
            return ResponseEntity.ok(kycService.getAllKYCs(KycStatus.valueOf(status.toUpperCase())));
        }
        return ResponseEntity.ok(kycService.getAllKYCs());
    }
}
