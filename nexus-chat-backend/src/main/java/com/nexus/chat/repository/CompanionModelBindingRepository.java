package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionModelBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanionModelBindingRepository extends JpaRepository<CompanionModelBinding, Long> {
    Optional<CompanionModelBinding> findByUserIdAndRoleId(Long userId, Long roleId);
}
