import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.List;
import java.util.Properties;

/**
 * The morning summary email. It exists because the PR comments are written AS
 * the principal: GitHub does not notify you of your own @mention, so without
 * this the principal would only find the results by going to look.
 */
final class Notifier {

    private final Config config;

    Notifier(Config config) { this.config = config; }

    void sendSummary(List<Report.Check> checks, String runDir) {
        if (!config.emailEnabled() || checks.isEmpty()) return;
        long safe = checks.stream().filter(c -> c.verdict == Report.Verdict.SAFE).count();
        StringBuilder body = new StringBuilder("Hi,\n\nThe mvnpm-dependabot elf checked ")
                .append(checks.size()).append(" Dependabot mvnpm PR(s) this morning:\n\n");
        String repo = null;
        for (Report.Check c : checks) {
            if (!c.target.repo().equals(repo)) {
                repo = c.target.repo();
                body.append("== ").append(repo).append(" ==\n\n");
            }
            body.append(switch (c.verdict) {
                case SAFE -> c.approve ? "[SAFE, approved]    " : "[SAFE, not approved] ";
                case NOT_SAFE -> "[NOT SAFE]          ";
                case NEEDS_HUMAN -> "[NEEDS A LOOK]      ";
            }).append(c.pr.title()).append('\n').append("  ").append(c.pr.url()).append('\n');
            for (String r : c.reasons) body.append("  - ").append(r).append('\n');
            body.append('\n');
        }
        body.append("Logs and screenshots: ").append(runDir).append('\n');
        send("mvnpm PRs: " + safe + "/" + checks.size() + " safe", body.toString());
    }

    private void send(String subject, String body) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", "smtp.gmail.com");
            props.put("mail.smtp.port", "587");
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.gmailAuthUser, config.gmailAppPassword);
                }
            });
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(config.gmailAddress));
            for (String to : config.sendTo.split(",")) {
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to.trim()));
            }
            msg.setSubject(subject);
            msg.setText(body, "utf-8");
            Transport.send(msg);
        } catch (Exception e) {
            System.err.println("Failed to send email: " + e.getMessage());
        }
    }
}
