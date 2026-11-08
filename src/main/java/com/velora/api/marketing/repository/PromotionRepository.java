package com.velora.api.marketing.repository;

import com.velora.api.marketing.domain.Promotion;
import com.velora.api.marketing.domain.PromotionStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionRepository extends JpaRepository<Promotion, Long>, JpaSpecificationExecutor<Promotion> {

    /** Every ACTIVE promotion; whether it is inside its window is decided by the clock at the moment of use. */
    List<Promotion> findByStatus(PromotionStatus status);

    /** Whether any order line was sold under this promotion: such a promotion is ended, never deleted. */
    @Query(value = "SELECT CASE WHEN EXISTS (SELECT 1 FROM order_item WHERE promotion_id = :id) THEN 1 ELSE 0 END",
            nativeQuery = true)
    int usedByOrders(@Param("id") Long id);
}
