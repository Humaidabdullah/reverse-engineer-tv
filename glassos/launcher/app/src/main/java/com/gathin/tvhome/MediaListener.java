package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself. Android only lets an app read other apps' media sessions (what is playing, its
 * artwork, play/pause/seek) while it holds a notification listener, so the phone remote's Now Playing
 * needs this declared and allowed: {@code cmd notification allow_listener com.gathin.tvhome/.MediaListener},
 * which PhoneRemote does by itself through AdbLoopback.
 */
public class MediaListener extends NotificationListenerService {
}
