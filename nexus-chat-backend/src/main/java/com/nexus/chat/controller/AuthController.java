package com.nexus.chat.controller;

import com.nexus.chat.dto.AuthResponse;
import com.nexus.chat.dto.LoginRequest;
import com.nexus.chat.dto.RegisterRequest;
import com.nexus.chat.dto.SendCodeRequest;
import com.nexus.chat.dto.VerifyCodeRequest;
import com.nexus.chat.model.EmailVerificationCode.CodeType;
import com.nexus.chat.service.AuthService;
import com.nexus.chat.service.VerificationCodeService;
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
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        log.info("用户注册请求: username={}, email={}", request.getUsername(), request.getEmail());
        try {
            AuthResponse response = authService.register(request);
            log.info("用户注册成功: userId={}, username={}", response.getUserId(), response.getUsername());
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.warn("用户注册失败: username={}, reason={}", request.getUsername(), e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        log.info("用户登录请求: usernameOrEmail={}", request.getUsernameOrEmail());
        try {
            AuthResponse response = authService.login(request);
            log.info("用户登录成功: userId={}, username={}", response.getUserId(), response.getUsername());
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.warn("用户登录失败: usernameOrEmail={}, reason={}", request.getUsernameOrEmail(), e.getMessage());
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestParam Long userId) {
        log.info("用户登出请求: userId={}", userId);
        authService.logout(userId);
        log.info("用户登出成功: userId={}", userId);
        return ResponseEntity.ok().build();
    }

}
