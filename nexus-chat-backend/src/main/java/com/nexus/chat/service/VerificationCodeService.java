package com.nexus.chat.service;

import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.EmailVerificationCode;
import com.nexus.chat.model.EmailVerificationCode.CodeType;
import com.nexus.chat.repository.EmailVerificationCodeRepository;
import com.nexus.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private final EmailVerificationCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${verification.code.expire-minutes:10}")
    private int expireMinutes;

    @Value("${verification.code.resend-interval-seconds:60}")
    private int resendIntervalSeconds;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public void sendVerificationCode(String email, CodeType type) {
        // Check if email already registered (for REGISTER type)
        if (type == CodeType.REGISTER && userRepository.existsByEmail(email)) {
            throw new BusinessException("该邮箱已被注册");
        }

        // Check resend interval
        Optional<EmailVerificationCode> lastCode = codeRepository
                .findTopByEmailAndTypeOrderByCreatedAtDesc(email, type);

        if (lastCode.isPresent()) {
            LocalDateTime canResendAfter = lastCode.get().getCreatedAt()
                    .plusSeconds(resendIntervalSeconds);
            if (LocalDateTime.now().isBefore(canResendAfter)) {
                long secondsLeft = java.time.Duration.between(
                        LocalDateTime.now(), canResendAfter).getSeconds();
                throw new BusinessException("请等待 " + secondsLeft + " 秒后再重新发送");
            }
        }

        // Generate new code
        String code = generateCode();

        // Mark old codes as used
        codeRepository.markAllAsUsed(email, type);

        // Save new code
        EmailVerificationCode verificationCode = new EmailVerificationCode();
        verificationCode.setEmail(email);
        verificationCode.setCode(code);
        verificationCode.setType(type);
        verificationCode.setExpiresAt(LocalDateTime.now().plusMinutes(expireMinutes));
        codeRepository.save(verificationCode);

        // Send email
        emailService.sendVerificationCode(email, code);

        log.info("验证码已发送: email={}, type={}", email, type);
    }

    @Transactional
    public boolean verifyCode(String email, String code, CodeType type) {
        Optional<EmailVerificationCode> verificationCode = codeRepository
                .findByEmailAndCodeAndTypeAndUsedFalse(email, code, type);

        if (verificationCode.isEmpty()) {
            log.warn("验证码不存在或已使用: email={}, code={}", email, code);
            return false;
        }

        EmailVerificationCode codeEntity = verificationCode.get();

        if (codeEntity.isExpired()) {
            log.warn("验证码已过期: email={}", email);
            return false;
        }

        // Mark as used
        codeEntity.setUsed(true);
        codeRepository.save(codeEntity);

        log.info("验证码验证成功: email={}", email);
        return true;
    }

    public boolean hasValidCode(String email, CodeType type) {
        return codeRepository.existsByEmailAndTypeAndUsedFalseAndExpiresAtAfter(
                email, type, LocalDateTime.now());
    }

    private String generateCode() {
        int code = RANDOM.nextInt(900000) + 100000;
        return String.valueOf(code);
    }

    @Transactional
    public void cleanupExpiredCodes() {
        codeRepository.deleteExpiredCodes(LocalDateTime.now());
        log.debug("已清理过期验证码");
    }
}
