package com.velora.api.shipping.repository;

import com.velora.api.catalog.domain.ShippingSizeClass;
import com.velora.api.shipping.domain.ShippingRate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShippingRateRepository extends JpaRepository<ShippingRate, Long> {

    /**
     * Every active rate for a governorate's zone — one per size class, in one query.
     *
     * <p>Because a governorate maps to exactly one zone, all rows returned belong to
     * the same zone and no two rows share a size class (unique on zone + size).
     */
    @EntityGraph(attributePaths = {"zone"})
    @Query("""
            select r from ShippingRate r
            join r.zone z
            join ShippingZoneGovernorate zg on zg.zone.id = z.id
            where zg.governorate.id = :governorateId
              and r.active = true
              and z.active = true
            order by r.sizeClass
            """)
    List<ShippingRate> findAllForGovernorate(@Param("governorateId") Long governorateId);

    @EntityGraph(attributePaths = {"zone"})
    List<ShippingRate> findByZoneIdAndActiveTrueOrderBySizeClassAsc(Long zoneId);

    Optional<ShippingRate> findByZoneIdAndSizeClass(Long zoneId, ShippingSizeClass sizeClass);
}
