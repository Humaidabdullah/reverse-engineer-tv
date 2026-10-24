package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the phone remote after the TV boots and after the launcher is updated, so a phone keeps working
 * even when Home has not been opened since (an update kills the launcher while another app is on screen).
 */
public class RemoteStarter extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ctx.getResources().getBoolean(R.bool.phone_remote)) PhoneRemote.start(ctx);
    }
}
