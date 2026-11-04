package com.velora.api.identity.repository;

import com.velora.api.identity.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** Email comparison is case-insensitive; the DB collation is CI but LOWER() makes it explicit. */
    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    Optional<AppUser> findByEmailIgnoreCase(@Param("email") String email);

    /** Pass an already-normalized E.164 number. */
    Optional<AppUser> findByPhoneE164(String phoneE164);

    /**
     * Login by either identifier in one query. Callers must pass the phone
     * already normalized to E.164.
     */
    @Query("""
            select u from AppUser u
            where lower(u.email) = lower(:identifier)
               or u.phoneE164 = :identifier
            """)
    Optional<AppUser> findByEmailOrPhone(@Param("identifier") String identifier);

    /** Accounts that hold at least one of the given roles (the staff list), without duplicates. */
    @Query("select distinct u from AppUser u join u.roles r where r.code in :codes order by u.id")
    java.util.List<AppUser> findByRoleCodes(@Param("codes") java.util.Collection<String> codes);

    boolean existsByPhoneE164(String phoneE164);

    @Query("select count(u) > 0 from AppUser u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);
}
