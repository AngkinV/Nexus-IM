package com.nexus.chat.service;

import com.nexus.chat.model.LoginHistory;
import com.nexus.chat.repository.LoginHistoryRepository;
import com.nexus.chat.util.UserAgentParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes a failed-attempt audit after the authentication transaction has released its locks. */
@Service
@RequiredArgsConstructor
public class LoginAuditService {
    private final LoginHistoryRepository history;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Long userId, String ip, String userAgent, String reason) {
        UserAgentParser.Result ua = UserAgentParser.parse(userAgent);
        LoginHistory entry = new LoginHistory();
        entry.setUserId(userId);
        entry.setSuccess(false);
        entry.setIpAddress(ip);
        entry.setDevice(ua.deviceName());
        entry.setBrowser(ua.browser());
        entry.setFailureReason(reason);
        history.save(entry);
    }
}
