package com.velora.api.shipping.repository;

import com.velora.api.shipping.domain.Governorate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GovernorateRepository extends JpaRepository<Governorate, Long> {

    List<Governorate> findByActiveTrueOrderByDisplayOrderAsc();

    List<Governorate> findAllByOrderByDisplayOrderAsc();

    Optional<Governorate> findByCode(String code);
}
