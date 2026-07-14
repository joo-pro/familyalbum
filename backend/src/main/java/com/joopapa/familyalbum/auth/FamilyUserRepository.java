package com.joopapa.familyalbum.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FamilyUserRepository extends JpaRepository<FamilyUser, UUID> {
    Optional<FamilyUser> findByKakaoId(String kakaoId);

    List<FamilyUser> findAllByOrderByCreatedAtDesc();
}