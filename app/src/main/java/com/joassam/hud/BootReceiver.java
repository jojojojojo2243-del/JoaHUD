package com.joassam.hud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 폰을 켜면 자동 모드 서비스 시작 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (c.getSharedPreferences("hud", Context.MODE_PRIVATE).getBoolean("auto_mode", false)) {
            AutoService.start(c);
        }
    }
}
