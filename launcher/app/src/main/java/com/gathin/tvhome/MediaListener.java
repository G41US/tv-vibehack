package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Only here so Android lets TV Home see what's playing (the Now playing card): media sessions are
 * visible to apps that hold notification access. TV Home doesn't read or keep any notifications.
 */
public class MediaListener extends NotificationListenerService {
}
