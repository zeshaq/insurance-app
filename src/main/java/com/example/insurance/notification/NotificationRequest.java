package com.example.insurance.notification;

/**
 * Payload sent to MI's /notification/dispatch endpoint. The {@code channel}
 * field is what MI's synapse {@code <switch>} routes on — same shape for
 * email/sms/push so the consumer never has to know about transport details.
 */
public record NotificationRequest(String channel, String recipient,
                                  String subject, String body) {}
