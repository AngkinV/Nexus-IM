package com.nexus.chat.service;

import com.nexus.chat.dto.AuthResponse;
import com.nexus.chat.dto.LoginRequest;
import com.nexus.chat.dto.RegisterRequest;
import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.EmailVerificationCode.CodeType;
import com.nexus.chat.model.User;
import com.nexus.chat.repository.UserRepository;
import com.nexus.chat.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final VerificationCodeService verificationCodeService;
    private final SecurityService securityService;

    @Transactional
    public AuthResponse register(RegisterRequest request, String ip, String userAgent) {
        // Verify the verification code first
        if (request.getVerificationCode() == null || request.getVerificationCode().isEmpty()) {
            throw new BusinessException("请输入邮箱验证码");
        }

        boolean codeValid = verificationCodeService.verifyCode(
                request.getEmail(),
                request.getVerificationCode(),
                CodeType.REGISTER
        );

        if (!codeValid) {
            throw new BusinessException("验证码无效或已过期");
        }

        // Check if username already exists
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException("error.auth.username.exists");
        }

        // Check if email already exists
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("error.auth.email.exists");
        }

        // Create new user
        User user = new User();
        user.setEmail(request.getEmail());
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setPhone(request.getPhone());
        user.setAvatarUrl(request.getAvatarUrl());
        user.setIsOnline(true);

        User savedUser = userRepository.save(user);

        // Seed security settings (password set time + strength) and the first session.
        securityService.recordPasswordChange(savedUser.getId(), request.getPassword());
        String sessionToken = securityService.recordSuccessfulLogin(savedUser.getId(), ip, userAgent);

        // Generate JWT token carrying the session id.
        String token = jwtTokenProvider.generateToken(savedUser.getId(), savedUser.getUsername(), sessionToken);

        return new AuthResponse(
                token,
                savedUser.getId(),
                savedUser.getUsername(),
                savedUser.getNickname(),
                savedUser.getAvatarUrl(),
                savedUser.getEmail(),
                savedUser.getPhone(),
                savedUser.getBio(),
                savedUser.getProfileBackground());
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ip, String userAgent) {
        String usernameOrEmail = request.getUsernameOrEmail();

        // Try to find user by username or email
        User user = userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail)
                .orElseThrow(() -> new BusinessException("error.auth.invalid.credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // Record the failed attempt against the known user before rejecting.
            securityService.recordFailedLogin(user.getId(), ip, userAgent, "invalid_password");
            throw new BusinessException("error.auth.invalid.credentials");
        }

        // Update online status
        user.setIsOnline(true);
        userRepository.save(user);

        // Track the device session and a success entry in login history.
        String sessionToken = securityService.recordSuccessfulLogin(user.getId(), ip, userAgent);

        // Generate JWT token carrying the session id.
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), sessionToken);

        return new AuthResponse(
                token,
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getEmail(),
                user.getPhone(),
                user.getBio(),
                user.getProfileBackground());
    }

    @Transactional
    public void logout(Long userId, String sessionToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        user.setIsOnline(false);
        userRepository.save(user);

        // Remove the device session so it stops showing as active.
        securityService.endSession(userId, sessionToken);
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));

        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("error.auth.password.incorrect");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new BusinessException("error.auth.password.too.short");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException("error.auth.password.same");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Persist the change time + recomputed strength for the security page.
        securityService.recordPasswordChange(userId, newPassword);
    }

}
