package com.nexus.chat.repository;

import com.nexus.chat.model.AppVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 应用版本仓库
 */
@Repository
public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {

    /** 查询最新的活跃版本 */
    Optional<AppVersion> findFirstByIsActiveTrueOrderByVersionCodeDesc();
}
