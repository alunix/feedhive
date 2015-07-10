/******************************************************************************
 * Copyright (C) 2012, 2013, 2014
 * Younghyung Cho. <yhcting77@gmail.com>
 * All rights reserved.
 *
 * This file is part of FeedHive
 *
 * This program is licensed under the FreeBSD license
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation
 * are those of the authors and should not be interpreted as representing
 * official policies, either expressed or implied, of the FreeBSD Project.
 *****************************************************************************/
package free.yhc.feeder;

import static free.yhc.feeder.model.Utils.eAssert;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import free.yhc.feeder.model.BGTask;
import free.yhc.feeder.model.Environ;
import free.yhc.feeder.model.RTTask;
import free.yhc.feeder.model.RTTask.Action;
import free.yhc.feeder.model.UnexpectedExceptionHandler;
import free.yhc.feeder.model.Utils;

public class LifeSupportService extends Service implements
UnexpectedExceptionHandler.TrackedModule {
    private static final boolean DBG = false;
    private static final Utils.Logger P = new Utils.Logger(LifeSupportService.class);

    public static final String ACTION_START = "feeder.intent.action.START_LIFE_SUPPORT";
    // Wakelock
    private static final String WLTAG = "free.yhc.feeder.LifeSupportService";

    private static PowerManager.WakeLock sWl = null;
    private static WifiManager.WifiLock  sWfl = null;
    private static int                   sWlcnt = 0;

    private static final TaskQListener   sTaskQListener = new TaskQListener();


    private static class TaskQListener implements RTTask.OnTaskQueueChangedListener {
        private int _mNrAction = 0;

        @Override
        public void
        onEnQ(BGTask task, long id, Action act) {
            if (Action.UPDATE == act
                || Action.DOWNLOAD == act) {
                if (0 == _mNrAction++)
                    // First action...
                    LifeSupportService.start();
            }
        }

        @Override
        public void
        onDeQ(BGTask task, long id, Action act) {
            if (Action.UPDATE == act
                || Action.DOWNLOAD == act) {
                if (0 == --_mNrAction)
                    LifeSupportService.stop();
            }
        }
    }


    public static void
    init() {
        RTTask.get().registerTaskQChangedListener(LifeSupportService.class, sTaskQListener);
    }

    public static void
    start() {
        if (DBG) P.v("Enter");
        Intent i = new Intent(Environ.getAppContext(), LifeSupportService.class);
        i.setAction(ACTION_START);
        Environ.getAppContext().startService(i);
    }

    public static void
    stop() {
        if (DBG) P.v("Enter");
        Intent i = new Intent(Environ.getAppContext(), LifeSupportService.class);
        Environ.getAppContext().stopService(i);
    }

    public static void
    getWakeLock() {
        eAssert(Utils.isUiThread());
        // getWakeLock() and putWakeLock() are used only at main ui thread (broadcast receiver, onStartCommand).
        // So, we don't need to synchronize it!
        eAssert(sWlcnt >= 0);
        if (null == sWl) {
            sWl = ((PowerManager)Environ.getAppContext().getSystemService(Context.POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WLTAG);
            sWfl = ((WifiManager)Environ.getAppContext().getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL, WLTAG);
            //logI("ScheduledUpdateService : WakeLock created and aquired");
            sWl.acquire();
            sWfl.acquire();
        }
        sWlcnt++;
        //logI("ScheduledUpdateService(GET) : current WakeLock count: " + mWlcnt);
    }

    public static void
    putWakeLock() {
        eAssert(Utils.isUiThread());

        if (sWlcnt <= 0)
            return; // nothing to put!

        eAssert(sWlcnt > 0);
        sWlcnt--;
        //logI("ScheduledUpdateService(PUT) : current WakeLock count: " + mWlcnt);
        if (0 == sWlcnt) {
            sWl.release();
            sWfl.release();
            // !! NOTE : Important.
            // if below line "mWl = null" is removed, then RuntimeException is raised
            //   when 'getWakeLock' -> 'putWakeLock' -> 'getWakeLock' -> 'putWakeLock(*)'
            //   (at them moment of location on * is marked - last 'putWakeLock').
            // That is, once WakeLock is released, reusing is dangerous in current Android Framework.
            // I'm not sure that this is Android FW's bug... or I missed something else...
            // Anyway, let's set 'mWl' as 'null' here to re-create new WakeLock at next time.
            sWl = null;
            sWfl = null;
            //logI("ScheduledUpdateService : WakeLock is released");
        }
    }



    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lvl) {
        return this.getClass().getName();
    }

    @Override
    public void
    onCreate() {
        if (DBG) P.v("Enter");
        super.onCreate();
        UnexpectedExceptionHandler.get().registerModule(this);
    }

    @Override
    public int
    onStartCommand(Intent intent, int flags, int startId) {
        if (!intent.getAction().equals(ACTION_START)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForeground(NotiManager.get().getForegroundNotificationId(),
                        NotiManager.get().getForegroundNotification());
        return START_NOT_STICKY;
    }

    @Override
    public void
    onDestroy() {
        if (DBG) P.v("Enter");
        UnexpectedExceptionHandler.get().unregisterModule(this);
        super.onDestroy();
    }

    @Override
    public IBinder
    onBind(Intent intent) {
        return null;
    }
}
