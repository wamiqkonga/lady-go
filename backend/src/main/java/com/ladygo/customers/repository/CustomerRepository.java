package com.ladygo.customers.repository;

import java.util.Optional;
import java.util.UUID;
import com.ladygo.customers.domain.Customer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByUserId(UUID userId);

    /**
     * Serializes concurrent count-then-insert calls for the same customer (saved locations, emergency
     * contacts) so two simultaneous requests can't both pass the limit check before either commits
     * (adversarial review finding, MEDIUM - a plain count-check has no such guarantee under READ
     * COMMITTED). Scoped to one customer's row; unrelated customers are unaffected.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> lockById(@Param("id") UUID id);

    /**
     * Admin customer list. Sentinels instead of nullable parameters (Postgres cannot type a bare
     * {@code null} bind): {@code hasStatus}/{@code hasQuery} switch the filters on, {@code pattern} is an
     * already-escaped lower-case LIKE pattern and {@code idMatch} the query parsed as a UUID (or a
     * never-matching zero UUID).
     */
    @Query(value = "select new com.ladygo.customers.repository.CustomerAdminRow(c.id, u.id, u.name, u.phone, "
            + "u.status, c.createdAt, u.lastLoginAt) from Customer c, User u where u.id = c.userId "
            + "and (:hasStatus = false or u.status = :status) "
            + "and (:hasQuery = false or lower(coalesce(u.name, '')) like :pattern escape '\\' "
            + "or u.phone like :pattern escape '\\' or c.id = :idMatch or u.id = :idMatch) "
            + "order by c.createdAt desc, c.id",
            countQuery = "select count(c) from Customer c, User u where u.id = c.userId "
            + "and (:hasStatus = false or u.status = :status) "
            + "and (:hasQuery = false or lower(coalesce(u.name, '')) like :pattern escape '\\' "
            + "or u.phone like :pattern escape '\\' or c.id = :idMatch or u.id = :idMatch)")
    org.springframework.data.domain.Page<CustomerAdminRow> searchForAdmin(
            @Param("hasStatus") boolean hasStatus, @Param("status") com.ladygo.users.domain.UserStatus status,
            @Param("hasQuery") boolean hasQuery, @Param("pattern") String pattern, @Param("idMatch") UUID idMatch,
            org.springframework.data.domain.Pageable pageable);
}
