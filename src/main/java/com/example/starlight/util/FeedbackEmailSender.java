package com.example.starlight.util;

import com.example.starlight.config.Endpoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.*;
import javax.mail.internet.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

/**
 * 反馈邮件发送器 —— 通过 139 邮箱 SMTP 发送用户反馈。
 * <p>发送方邮箱由 UI 输入框提供，SMTP 登录账号、授权码和收件邮箱取自
 * {@code /assets/endpoints.json}（见 {@link com.example.starlight.config.Endpoints}）。</p>
 */
public class FeedbackEmailSender {

    private static final Logger log = LoggerFactory.getLogger(FeedbackEmailSender.class);



    private static final String DEFAULT_HOST = Endpoints.smtpHost();
    private static final String DEFAULT_PORT = Endpoints.smtpPort();
    private static final String DEFAULT_USER = Endpoints.smtpUser();
    private static final String DEFAULT_PASS = Endpoints.smtpPassword();
    private static final String DEFAULT_TO   = Endpoints.smtpTo();

    /** 发送反馈邮件
     * @param senderEmail 用户填写的发送方邮箱（用于邮件 From/Reply-To）
     * @param userEmail   用户反馈联系邮箱
     * @param content     反馈内容
     * @param imageFile   可选附件图片 */
    public static boolean sendFeedback(String senderEmail,
                                        String userEmail, String content, File imageFile) {
        try {
            String host   = DEFAULT_HOST;
            String port   = DEFAULT_PORT;
            String mailUser = DEFAULT_USER;
            String mailPass = DEFAULT_PASS;
            String mailTo = DEFAULT_TO;

            if (senderEmail == null || senderEmail.isEmpty()) {
                log.warn("sendFeedback: senderEmail is empty");
                return false;
            }

            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", port);
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.protocols", "TLSv1.2");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(mailUser, mailPass);
                }
            });

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(mailUser, "Starlight"));
            msg.setReplyTo(new InternetAddress[]{new InternetAddress(senderEmail)});
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(mailTo));
            msg.setSubject("Starlight Feedback - " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));

            StringBuilder body = new StringBuilder();
            body.append("<html><body style='font-family: sans-serif; padding:20px;'>");
            body.append("<h2>Feedback</h2><hr/>");
            body.append("<p><b>Email:</b> ").append(escapeHtml(userEmail)).append("</p>");
            body.append("<p><b>Time:</b> ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("</p>");
            body.append("<hr/><pre>").append(escapeHtml(content)).append("</pre>");
            body.append("</body></html>");

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setContent(body.toString(), "text/html; charset=UTF-8");

            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);

            if (imageFile != null && imageFile.exists() && imageFile.isFile()) {
                MimeBodyPart attachPart = new MimeBodyPart();
                DataSource source = new FileDataSource(imageFile);
                attachPart.setDataHandler(new DataHandler(source));
                attachPart.setFileName(MimeUtility.encodeText(imageFile.getName(), "UTF-8", null));
                multipart.addBodyPart(attachPart);
            }

            msg.setContent(multipart);
            msg.saveChanges();
            Transport.send(msg);
            log.info("Feedback sent to {}", mailTo);
            return true;
        } catch (Exception e) {
            log.error("send failed", e);
            return false;
        }
    }

    public static boolean isConfigured() {
        return true;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
