package com.nexus.chat.controller;

import com.nexus.chat.model.AppVersion;
import com.nexus.chat.repository.AppVersionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 应用版本控制器
 */
@RestController
@RequestMapping("/api/app")
public class AppVersionController {

    @Autowired
    private AppVersionRepository appVersionRepository;

    /**
     * 检查更新
     * GET /api/app/check-update?currentVersionCode=1
     */
    @GetMapping("/check-update")
    public ResponseEntity<?> checkUpdate(@RequestParam Integer currentVersionCode) {
        try {
            var latest = appVersionRepository.findFirstByIsActiveTrueOrderByVersionCodeDesc();

            if (latest.isEmpty() || latest.get().getVersionCode() <= currentVersionCode) {
                Map<String, Object> result = new HashMap<>();
                result.put("hasUpdate", false);
                return ResponseEntity.ok(result);
            }

            AppVersion version = latest.get();
            Map<String, Object> result = new HashMap<>();
            result.put("hasUpdate", true);
            result.put("versionName", version.getVersionName());
            result.put("versionCode", version.getVersionCode());
            result.put("downloadUrl", version.getDownloadUrl());
            result.put("updateLog", version.getUpdateLog());
            result.put("fileSize", version.getFileSize());
            result.put("forceUpdate", version.getForceUpdate());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
}
