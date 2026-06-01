package com.example.TestAPI.DTO.Job;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public record CreateJobRequest(
        @NotBlank(message = "Le titre est obligatoire")
        String title,

        String description,

        String location,

        @NotNull(message = "Le prix est obligatoire")
        @Positive(message = "Le prix doit être positif")
        BigDecimal price,

        List<String> images,

        UUID categoryId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        @Future(message = "La date limite doit être dans le futur")
        Date applicationDeadline
) { }
