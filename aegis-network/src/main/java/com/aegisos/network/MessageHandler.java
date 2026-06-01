package com.aegisos.network;

import com.aegisos.core.message.AegisMessage;

/**
 * Handles an inbound application message. Return a non-null {@link AegisMessage} to send
 * a correlated reply back to the sender, or {@code null} for fire-and-forget messages.
 */
@FunctionalInterface
public interface MessageHandler {
    AegisMessage handle(AegisMessage request);
}
