package com.christos_bramis.bram_vortex_pipeline_generator.repository;

import com.christos_bramis.bram_vortex_pipeline_generator.entity.PipelineJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PipelineJobRepository extends JpaRepository<PipelineJob, String> {
    // Μπορεί να χρειαστείς να βρεις το Terraform Job με βάση το Analysis Job
    Optional<PipelineJob> findByAnalysisJobId(String analysisJobId);
}