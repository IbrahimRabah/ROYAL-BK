package com.velora.api.notification.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends HTML email. Gmail SMTP for now (500/day free, {@code spring.mail.*}) — the
 * only thing that changes when this moves to a transactional provider (SES, Postmark)
 * is configuration, because nothing outside this class talks to {@link JavaMailSender}
 * directly.
 *
 * <p>A send failure is logged and swallowed, never thrown. This is called from an
 * {@code AFTER_COMMIT} listener — the order the email describes has already been
 * placed successfully, so a broken mail server must not look like a failed checkout.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public MailService(JavaMailSender mailSender,
                       @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void sendHtml(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Sent email to {} — {}", to, subject);
        } catch (MessagingException | MailException ex) {
            log.error("Failed to send email to {} — {}: {}", to, subject, ex.getMessage(), ex);
        }
    }
}
