package com.example.MigrosBackend.exception.user;

public class MailSendingFailedException extends RuntimeException {
    public MailSendingFailedException() {
        super("Mail sending failed. Please try again later.");
    }
}
