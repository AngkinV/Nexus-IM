package com.nexus.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Async
    public void sendVerificationCode(String toEmail, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("【Nexus Chat】邮箱验证码");

            String htmlContent = buildVerificationEmailTemplate(code);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("验证码邮件发送成功: email={}", toEmail);
        } catch (MessagingException e) {
            log.error("验证码邮件发送失败: email={}, error={}", toEmail, e.getMessage());
            throw new RuntimeException("邮件发送失败，请稍后重试");
        }
    }

    private String buildVerificationEmailTemplate(String code) {
        String template = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f5f5f5; margin: 0; padding: 20px; }
                    .container { max-width: 500px; margin: 0 auto; background: white; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.1); overflow: hidden; }
                    .header { background: linear-gradient(135deg, #2481cc, #1a6fb0); color: white; padding: 30px; text-align: center; }
                    .header h1 { margin: 0; font-size: 24px; }
                    .content { padding: 30px; text-align: center; }
                    .code-box { background: #f8f9fa; border: 2px dashed #2481cc; border-radius: 8px; padding: 20px; margin: 20px 0; }
                    .code { font-size: 32px; font-weight: bold; color: #2481cc; letter-spacing: 8px; }
                    .info { color: #666; font-size: 14px; margin-top: 20px; }
                    .warning { color: #e74c3c; font-size: 12px; margin-top: 15px; }
                    .footer { background: #f8f9fa; padding: 20px; text-align: center; color: #999; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>Nexus Chat</h1>
                    </div>
                    <div class="content">
                        <p>您好！</p>
                        <p>您正在注册 Nexus Chat 账号，请使用以下验证码完成验证：</p>
                        <div class="code-box">
                            <span class="code">{{CODE}}</span>
                        </div>
                        <p class="info">验证码有效期为 <strong>10分钟</strong>，请尽快完成验证。</p>
                        <p class="warning">如果这不是您本人的操作，请忽略此邮件。</p>
                    </div>
                    <div class="footer">
                        <p>此邮件由系统自动发送，请勿直接回复。</p>
                        <p>© {{YEAR}} Nexus Chat. All rights reserved.</p>
                    </div>
                </div>
            </body>
            </html>
            """;
        return template
            .replace("{{CODE}}", code)
            .replace("{{YEAR}}", String.valueOf(java.time.Year.now().getValue()));
    }
}
