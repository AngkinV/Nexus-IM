package com.nexus.chat.service;

import com.nexus.chat.model.CompanionRole;
import com.nexus.chat.model.CompanionStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CompanionFallbackService {

    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "难过", "伤心", "焦虑", "不开心", "生气", "压力", "失眠", "烦", "难受", "崩溃", "害怕", "紧张");

    private static final List<String> ACTION_KEYWORDS = List.of(
            "怎么办", "如何", "怎么", "建议", "需要", "帮我", "帮助");

    public String generateReply(CompanionRole role, String userContent) {
        String name = role != null ? role.getName() : "我";

        if (containsAny(userContent, NEGATIVE_KEYWORDS)) {
            return name + "在这儿陪着你。听起来有点不容易，我们可以慢慢聊。";
        }
        if (containsAny(userContent, ACTION_KEYWORDS)) {
            return "我理解你的想法。要不要先从一个很小的动作开始？比如写下三件能让你感觉好一点的事。";
        }
        return "我在的。你可以继续说，我会认真听。";
    }

    public CompanionStatus.StatusType pickStatus(LocalDateTime now) {
        int hour = now.getHour();
        if (hour >= 6 && hour < 9) return CompanionStatus.StatusType.writing;
        if (hour >= 9 && hour < 12) return CompanionStatus.StatusType.organizing;
        if (hour >= 12 && hour < 14) return CompanionStatus.StatusType.listening;
        if (hour >= 14 && hour < 18) return CompanionStatus.StatusType.reading;
        if (hour >= 18 && hour < 21) return CompanionStatus.StatusType.walking;
        return CompanionStatus.StatusType.resting;
    }

    public String generateStatusSummary(CompanionRole role, CompanionStatus.StatusType type) {
        String name = role != null ? role.getName() : "我";
        return switch (type) {
            case reading -> name + "在安静读点东西。";
            case listening -> name + "在听一会儿音乐。";
            case walking -> name + "在散散步，放空一下。";
            case thinking -> name + "在整理思绪。";
            case chatting -> name + "正在陪你聊天。";
            case writing -> name + "在写点小记。";
            case organizing -> name + "在整理清单。";
            case resting -> name + "在休息一会儿。";
        };
    }

    private boolean containsAny(String content, List<String> keywords) {
        if (content == null) return false;
        for (String key : keywords) {
            if (content.contains(key)) return true;
        }
        return false;
    }
}
