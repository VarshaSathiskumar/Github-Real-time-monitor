package com.githubpulse.analytics.store;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessingFailureRepository extends JpaRepository<ProcessingFailure, Long> {

    List<ProcessingFailure> findTop20ByOrderByFailedAtDesc();
}
