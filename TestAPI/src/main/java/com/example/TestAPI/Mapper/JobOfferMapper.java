package com.example.TestAPI.Mapper;

import com.example.TestAPI.DTO.Job.JobResponse;
import com.example.TestAPI.Model.JobOffer;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface JobOfferMapper {

    JobOfferMapper INSTANCE = Mappers.getMapper(JobOfferMapper.class);

    @Mapping(source = "creator.id", target = "creatorId")
    @Mapping(source = "creator.username", target = "creatorUsername")
    @Mapping(source = "worker.id", target = "workerId")
    @Mapping(source = "worker.username", target = "workerUsername")
    @Mapping(source = "status", target = "status")
    @Mapping(target = "images", expression = "java(job.getImages() == null ? java.util.List.of() : job.getImages())")
    @Mapping(target = "categoryId", expression = "java(job.getCategory() != null ? job.getCategory().getId() : null)")
    @Mapping(target = "categoryName", expression = "java(job.getCategory() != null ? job.getCategory().getName() : null)")
    @Mapping(target = "applicationDeadline", source = "applicationDeadline")
    JobResponse toDTO(JobOffer job);
}