package com.velora.api.marketing.repository;

import com.velora.api.marketing.domain.Announcement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    List<Announcement> findByActiveTrueOrderByPositionAscIdAsc();

    List<Announcement> findAllByOrderByPositionAscIdAsc();

    boolean existsByPromotionId(Long promotionId);
}
