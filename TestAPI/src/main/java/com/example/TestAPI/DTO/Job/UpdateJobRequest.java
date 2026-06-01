package com.example.TestAPI.DTO.Job;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public record UpdateJobRequest(
        @Size(min = 3, max = 100, message = "Le titre doit contenir entre 3 et 100 caractères")
        String title,

        @Size(max = 500, message = "La description ne peut pas dépasser 500 caractères")
        String description,

        String location,

        @Positive(message = "Le prix doit être positif")
        BigDecimal price,

        List<String> images,

        UUID categoryId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        @Future(message = "La date limite doit être dans le futur")
        Date applicationDeadline
) { }
