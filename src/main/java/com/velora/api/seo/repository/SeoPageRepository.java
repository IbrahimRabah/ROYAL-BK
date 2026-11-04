package com.velora.api.seo.repository;

import com.velora.api.seo.domain.SeoPage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeoPageRepository extends JpaRepository<SeoPage, Long> {

    Optional<SeoPage> findByPageKey(String pageKey);

    List<SeoPage> findAllByOrderByIdAsc();
}
