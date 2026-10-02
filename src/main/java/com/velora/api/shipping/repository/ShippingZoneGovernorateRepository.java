package com.velora.api.shipping.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Which zone each governorate belongs to. The governorate id is the primary key, so
 * there is at most one row per governorate — and no row at all means "not served".
 *
 * <p>A plain class over the {@link EntityManager} rather than a Spring Data interface:
 * {@code ShippingZoneGovernorate}'s identifier is an association, which Spring Data's
 * repository metadata rejects ("does not define an IdClass"). Native SQL keeps this
 * independent of that mapping.
 *
 * <p>Every write flushes and clears the persistence context, so a later read in the same
 * transaction cannot see a stale zone for the governorate.
 */
@Repository
public class ShippingZoneGovernorateRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<Long> findZoneIdByGovernorateId(Long governorateId) {
        List<?> rows = entityManager
                .createNativeQuery("select zone_id from shipping_zone_governorate "
                        + "where governorate_id = :governorateId")
                .setParameter("governorateId", governorateId)
                .getResultList();
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(((Number) rows.get(0)).longValue());
    }

    public void insert(Long governorateId, Long zoneId) {
        execute("insert into shipping_zone_governorate (governorate_id, zone_id) "
                + "values (:governorateId, :zoneId)", governorateId, zoneId);
    }

    public void moveToZone(Long governorateId, Long zoneId) {
        execute("update shipping_zone_governorate set zone_id = :zoneId "
                + "where governorate_id = :governorateId", governorateId, zoneId);
    }

    /** @return how many rows were removed — zero when it was already closed */
    public int deleteByGovernorateId(Long governorateId) {
        entityManager.flush();
        int removed = entityManager
                .createNativeQuery("delete from shipping_zone_governorate "
                        + "where governorate_id = :governorateId")
                .setParameter("governorateId", governorateId)
                .executeUpdate();
        entityManager.clear();
        return removed;
    }

    private void execute(String sql, Long governorateId, Long zoneId) {
        entityManager.flush();
        entityManager.createNativeQuery(sql)
                .setParameter("governorateId", governorateId)
                .setParameter("zoneId", zoneId)
                .executeUpdate();
        entityManager.clear();
    }
}
