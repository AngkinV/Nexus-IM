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

    @Transactional
    public AuthResponse register(RegisterRequest request) {
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

        // Generate JWT token
        String token = jwtTokenProvider.generateToken(savedUser.getId(), savedUser.getUsername());

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
    public AuthResponse login(LoginRequest request) {
        String usernameOrEmail = request.getUsernameOrEmail();

        // Try to find user by username or email
        User user = userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail)
                .orElseThrow(() -> new BusinessException("error.auth.invalid.credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException("error.auth.invalid.credentials");
        }

        // Update online status
        user.setIsOnline(true);
        userRepository.save(user);

        // Generate JWT token
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());

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
    public void logout(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("error.user.not.found"));
        user.setIsOnline(false);
        userRepository.save(user);
    }

}
