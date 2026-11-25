package com.nexus.chat.service;

import com.nexus.chat.exception.BusinessException;
import com.nexus.chat.model.InviteCode;
import com.nexus.chat.repository.InviteCodeRepository;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Who may register. Both switches default to how registration has always worked; the
 * {@code public} profile asks for an invite code and stops using email codes.
 */
@Service
public class RegistrationPolicy {
    /** The one answer for a missing, unknown, expired, used-up or revoked code. */
    public static final String INVITE_REJECTED = "邀请码无效或已失效";
    public static final String EMAIL_CODE_DISABLED = "当前注册方式不使用邮箱验证码";

    private final InviteCodeRepository inviteCodes;
    @Getter
    private final boolean inviteRequired;
    @Getter
    private final boolean emailCodeRequired;

    public RegistrationPolicy(InviteCodeRepository inviteCodes,
                              @Value("${nexus.registration.invite-required:false}") boolean inviteRequired,
                              @Value("${nexus.registration.email-code-required:true}") boolean emailCodeRequired) {
        this.inviteCodes = inviteCodes;
        this.inviteRequired = inviteRequired;
        this.emailCodeRequired = emailCodeRequired;
    }

    /** Gate for sending and checking email codes. The invite is checked, not consumed. */
    public void requireEmailCodeAllowed(String inviteCode) {
        if (!emailCodeRequired) {
            throw new BusinessException(EMAIL_CODE_DISABLED);
        }
        if (inviteRequired && (inviteCode == null || !inviteCodes.isUsable(InviteCode.hash(inviteCode), LocalDateTime.now()))) {
            throw new BusinessException(INVITE_REJECTED);
        }
    }

    /**
     * Takes one use of the invite. It joins the caller's transaction, so a registration that
     * fails afterwards rolls the use back.
     */
    public void consumeInvite(String inviteCode) {
        if (!inviteRequired) {
            return;
        }
        if (inviteCode == null || inviteCodes.consume(InviteCode.hash(inviteCode), LocalDateTime.now()) != 1) {
            throw new BusinessException(INVITE_REJECTED);
        }
    }
}
