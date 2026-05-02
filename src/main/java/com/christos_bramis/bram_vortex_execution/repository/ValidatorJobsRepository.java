package com.christos_bramis.bram_vortex_execution.repository;

import com.christos_bramis.bram_vortex_execution.entity.ValidatorJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ValidatorJobsRepository extends JpaRepository<ValidatorJob, String> {
}
