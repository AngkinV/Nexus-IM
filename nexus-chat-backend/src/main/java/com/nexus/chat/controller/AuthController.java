package com.nexus.chat.controller;

import com.nexus.chat.dto.AuthResponse;
import com.nexus.chat.dto.ChangePasswordRequest;
import com.nexus.chat.dto.LoginRequest;
import com.nexus.chat.dto.RegisterRequest;
import com.nexus.chat.dto.SendCodeRequest;
import com.nexus.chat.dto.VerifyCodeRequest;
import com.nexus.chat.model.EmailVerificationCode.CodeType;
import com.nexus.chat.security.JwtTokenProvider;
import com.nexus.chat.service.AuthService;
import com.nexus.chat.service.VerificationCodeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final VerificationCodeService verificationCodeService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/send-code")
    public ResponseEntity<?> sendVerificationCode(@RequestBody SendCodeRequest request) {
        log.info("发送验证码请求: email={}, type={}", request.getEmail(), request.getType());
        try {
            CodeType type = CodeType.valueOf(request.getType().toUpperCase());
            verificationCodeService.sendVerificationCode(request.getEmail(), type);
            return ResponseEntity.ok(java.util.Map.of(
                "message", "验证码已发送",
                "success", true
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "无效的验证码类型"));
        } catch (RuntimeException e) {
            log.warn("发送验证码失败: email={}, reason={}", request.getEmail(), e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/verify-code")
    public ResponseEntity<?> verifyCode(@RequestBody VerifyCodeRequest request) {
        log.info("验证验证码请求: email={}", request.getEmail());
        try {
            CodeType type = CodeType.valueOf(request.getType().toUpperCase());
            boolean valid = verificationCodeService.verifyCode(request.getEmail(), request.getCode(), type);
            if (valid) {
                return ResponseEntity.ok(java.util.Map.of(
                    "message", "验证成功",
                    "success", true
                ));
            } else {
                return ResponseEntity.badRequest().body(java.util.Map.of(
                    "message", "验证码无效或已过期",
                    "success", false
                ));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "无效的验证码类型"));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        log.info("用户注册请求: username={}, email={}", request.getUsername(), request.getEmail());
        try {
            AuthResponse response = authService.register(request, clientIp(httpRequest), userAgent(httpRequest));
            log.info("用户注册成功: userId={}, username={}", response.getUserId(), response.getUsername());
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.warn("用户注册失败: username={}, reason={}", request.getUsername(), e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        log.info("用户登录请求: usernameOrEmail={}", request.getUsernameOrEmail());
        try {
            AuthResponse response = authService.login(request, clientIp(httpRequest), userAgent(httpRequest));
            log.info("用户登录成功: userId={}, username={}", response.getUserId(), response.getUsername());
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.warn("用户登录失败: usernameOrEmail={}, reason={}", request.getUsernameOrEmail(), e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestParam Long userId, HttpServletRequest httpRequest) {
        log.info("用户登出请求: userId={}", userId);
        authService.logout(userId, bearerSessionToken(httpRequest));
        log.info("用户登出成功: userId={}", userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestParam Long userId,
                                            @RequestBody ChangePasswordRequest request) {
        log.info("修改密码请求: userId={}", userId);
        try {
            authService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
            log.info("修改密码成功: userId={}", userId);
            return ResponseEntity.ok(java.util.Map.of("message", "密码修改成功", "success", true));
        } catch (RuntimeException e) {
            log.warn("修改密码失败: userId={}, reason={}", userId, e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage(), "success", false));
        }
    }

    /** Best-effort client IP, honoring a reverse proxy's forwarding headers. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // "client, proxy1, proxy2" -> first hop is the real client.
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }

    /** Extract the session id from the request's Bearer token, if present. */
    private String bearerSessionToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return jwtTokenProvider.getSessionTokenFromToken(header.substring(7));
        }
        return null;
    }

}
