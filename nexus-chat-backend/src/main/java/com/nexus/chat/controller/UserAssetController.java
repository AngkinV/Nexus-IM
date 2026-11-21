package com.nexus.chat.controller;

import com.nexus.chat.security.CurrentUser;
import com.nexus.chat.service.UserAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** User-owned settings assets. Ownership always comes from the authenticated principal. */
@RestController
@RequestMapping("/api/companion/user-assets")
@RequiredArgsConstructor
public class UserAssetController {
    private final UserAssetService assets;

    @GetMapping("/models")
    public List<Map<String, Object>> models() throws IOException {
        return assets.listModels(CurrentUser.require());
    }
    @GetMapping("/motions")
    public Map<String, Object> motions() throws IOException {
        return assets.listMotions(CurrentUser.require());
    }
    @PostMapping("/{kind}")
    public Map<String, Object> upload(@PathVariable String kind, @RequestParam("file") MultipartFile file) throws IOException {
        return assets.upload(CurrentUser.require(), kind, file);
    }
    @PutMapping("/{kind}/{id}/name")
    public Map<String, Object> rename(@PathVariable String kind, @PathVariable String id,
                                    @RequestBody Map<String, String> body) throws IOException {
        return assets.rename(CurrentUser.require(), kind, id, body.get("name"));
    }
    @DeleteMapping("/motion/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        assets.deleteMotion(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/file/{kind}/{id}")
    public ResponseEntity<Resource> file(@PathVariable String kind, @PathVariable String id) throws IOException {
        Path path = assets.file(CurrentUser.require(), kind, id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
    }
}
