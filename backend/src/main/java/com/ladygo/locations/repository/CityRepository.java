package com.ladygo.locations.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.locations.domain.City;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CityRepository extends JpaRepository<City, UUID> {

    List<City> findByActiveTrue();

    Optional<City> findByNameAndCountry(String name, String country);
}
