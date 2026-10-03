package com.ladygo.users.repository;

import java.util.Optional;
import java.util.UUID;
import com.ladygo.users.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    Optional<User> findByEmailIgnoreCase(String email);
}
