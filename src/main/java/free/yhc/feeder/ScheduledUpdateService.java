/******************************************************************************
 * Copyright (C) 2012, 2013, 2014, 2015, 2016
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

import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.IBinder;
import android.support.annotation.NonNull;

import free.yhc.abaselib.AppEnv;
import free.yhc.baselib.Logger;
import free.yhc.baselib.async.TaskBase;
import free.yhc.abaselib.util.UxUtil;
import free.yhc.feeder.core.Environ;
import free.yhc.feeder.core.Util;
import free.yhc.feeder.task.UpdateTask;
import free.yhc.feeder.db.ColumnChannel;
import free.yhc.feeder.db.DBPolicy;
import free.yhc.feeder.core.Err;
import free.yhc.feeder.core.RTTask;
import free.yhc.feeder.core.UnexpectedExceptionHandler;

import static free.yhc.baselib.util.Util.convertArrayLongTolong;
import static free.yhc.abaselib.util.AUtil.isUiThread;

// There is no way to notify result of scheduled-update to user.
// So, even if scheduled update may fail, there is no explicit notification.
// But user can know whether scheduled-update successes or fails by checking age since last successful update.
// (See channelListAdapter for 'age' time)
public class ScheduledUpdateService extends Service implements
UnexpectedExceptionHandler.TrackedModule {
    private static final boolean DBG = Logger.DBG_DEFAULT;
    private static final Logger P = Logger.create(ScheduledUpdateService.class, Logger.LOGLV_DEFAULT);

    // Should match manifest's intent filter
    private static final String SCHEDUPDATE_INTENT_ACTION = "feeder.intent.action.SCHEDULED_UPDATE";

    private static final String CMD_ALARM = "alarm";
    private static final String CMD_RESCHED = "resched";
    private static final String CMD_UPDATE = "update";

    private static final String KEY_CMD = "cmd";
    private static final String KEY_TIME = "time";
    private static final String KEY_CHANS = "chans";

    private static final int RETRY_DELAY = 1000; // ms

    // Number of running service command
    // This is used to check whether there is running scheduled update service instance or not.
    // NOTE
    // This variable only be accessed by main UI thread!
    private static int mSrvcnt = 0;

    // Scheduled update is enabled/disabled.
    // If disabled, requested command is continuously posted to message Q until
    //   scheduled update is re-enabled again.
    private static boolean mEnabled = true;

    private final DBPolicy mDbp = DBPolicy.get();
    private final RTTask mRtt = RTTask.get();
    private final UpdateTaskListener mUpdateTaskListener = new UpdateTaskListener();
    private final AtomicInteger mTaskCnt = new AtomicInteger(0);

    // If time is changed Feeder need to re-scheduling scheduled-update.
    public static class DateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // TODO
            // At some devices, time is set whenever device back to active from sleep.
            // So, this receiver is called too often.
            // To avoid this, just ignore this intent until any better solution is found.
            // => Back to active this code again. Rescheduling often is no harmful and
            //      sometimes it keep App. safe from unexpected-not-scheduled-bug.
            //
            // FIXME
            // Because Feeder ignores this intent, when system time is changed,
            //   scheduled update isn't re-scheduled!
            // This is bug, but in most cases, system time is not changed manually.
            // So, let's ignore this exceptional case.
            //
            // TODO
            // Any better way??
            Intent svc = new Intent(context, ScheduledUpdateService.class);
            svc.putExtra(KEY_CMD, CMD_RESCHED);
            // onStartCommand will be sent!
            context.startService(svc);
            // Update should be started before falling into sleep.
            // Should we acquire wakelock here?
            // LifeSupportService.getWakeLock();
        }
    }

    // Why broadcast receiver?
    // Using pendingIntent from "PendingIntent.getService()" doesn't guarantee that
    //   given service gets controls before device falls into sleep.
    // But broadcast receiver guarantee that device doesn't fall into sleep before returning from 'onReceive'
    // (This is described at Android document)
    // So, broadcast receiver is used event if it's more complex than using "PendingIntent.getService()" directly.
    public static class AlarmReceiver extends BroadcastReceiver {
        // NOTE:
        //   broadcast receiver is run on main ui thread (same as service).
        @Override
        public void
        onReceive(Context context, Intent intent) {
            //logI("AlarmReceiver : onReceive");
            long time = intent.getLongExtra("time", -1);
            if (time < 0) {
                P.bug(false);
                return;
            }
            Intent svc = new Intent(context, ScheduledUpdateService.class);
            svc.putExtra(KEY_CMD, CMD_ALARM);
            svc.putExtra(KEY_TIME, time);
            // onStartCommand will be sent!
            context.startService(svc);
            // Update should be started before falling into sleep.
            // Should we acquire wakelock here?
            // LifeSupportService.getWakeLock();
        }
    }

    private class UpdateTaskListener extends TaskBase.EventListener<UpdateTask, Err> {
        private void
        handleTaskDone(@NonNull UpdateTask task) {
            P.bug(mTaskCnt.get() > 0);
            if (0 >= mTaskCnt.decrementAndGet())
                stopSelf();
        }

        @Override
        public void
        onCancelled(@NonNull UpdateTask task, Object param) {
            //logI("ScheduledUpdateService(onCancel) : " + cid);
            handleTaskDone(task);
        }

        @Override
        public void
        onPostRun(@NonNull UpdateTask task, Err result, Exception ex) {
            handleTaskDone(task);
        }

    }

    private class StartCmdPost implements Runnable {
        private Intent _mIntent;
        private int _mFlags;
        private int _mStartId;
        StartCmdPost(Intent intent, int flags, int startId) {
            _mIntent = intent;
            _mFlags = flags;
            _mStartId = startId;
        }

        @Override
        public void run() {
            // try after 500 ms with same runnable instance.
            if (!ScheduledUpdateService.isEnabled()) {
                //logI("runStartCommand --- POST!!!");
                AppEnv.getUiHandler().postDelayed(this, RETRY_DELAY);
            } else
                runStartCommand(_mIntent, _mFlags, _mStartId);
        }
    }

    private static class ChannelValue {
        long cid;
        long v;
        ChannelValue(long cid, long v) {
            this.cid = cid;
            this.v = v;
        }
    }

    @SuppressWarnings("unused")
    private static class ChannelValueComparator implements Comparator<ChannelValue> {
        @Override
        public int compare(ChannelValue cv0, ChannelValue cv1) {
            if (cv0.v < cv1.v)
                return -1;
            else if (cv0.v > cv1.v)
                return 1;
            else
                return 0;
        }
    }

    // =======================================================
    //
    // Function to handle race-condition regarding DB access!
    //
    // =======================================================

    /**
     * Should be called on main UI thread.
     */
    private static void
    incInstanceCount() {
        P.bug(isUiThread() && mSrvcnt >= 0);
        mSrvcnt++;
    }

    /**
     * Should be called on main UI thread.
     */
    private static void
    decInstanceCount() {
        P.bug(isUiThread() && mSrvcnt > 0);
        mSrvcnt--;
    }

    /**
     * Should be called on main UI thread.
     */
    public static boolean
    doesInstanceExist() {
        P.bug(isUiThread());
        return mSrvcnt > 0;
    }

    /**
     * This is very dangerous! (May lead to infinite loop!)
     * So, DO NOT USER this function if you don't know what your are doing!
     * Should be called on main UI thread.
     */
    public static void
    enable() {
        P.bug(isUiThread());
        mEnabled = true;
    }

    /**
     * This is very dangerous! (May lead to infinite loop!)
     * So, DO NOT USER this function if you don't know what your are doing!
     * Should be called on main UI thread.
     */
    public static void
    disable() {
        P.bug(isUiThread());
        mEnabled = false;
    }

    /**
     * Should be called on main UI thread.
     */
    private static boolean
    isEnabled() {
        P.bug(isUiThread());
        return mEnabled;
    }

    // =======================================================
    //
    //
    //
    // =======================================================

    // out[0] : time to go from dayTime0 to dayTime1
    // out[1] : time to go from dayTime1 to dayTime0
    private static void
    dayBasedDistanceMs(long[] out, long dayms0, long dayms1) {
        P.bug(dayms0 <= Util.DAY_IN_MS && dayms1 <= Util.DAY_IN_MS);
        if (dayms0 > dayms1) {
            out[1] = dayms0 - dayms1;
            out[0] = Util.DAY_IN_MS - out[1];
        } else if (dayms0 < dayms1) {
            out[0] = dayms1 - dayms0;
            out[1] = Util.DAY_IN_MS - out[0];
        }
    }

    /**
     * Schedule update for 'cids' right now.
     */
    static void
    scheduleImmediateUpdate(long[] cids) {
        Context context = AppEnv.getAppContext();
        Intent svc = new Intent(context, ScheduledUpdateService.class);
        svc.putExtra(KEY_CMD, CMD_UPDATE);
        svc.putExtra(KEY_CHANS, cids);
        // onStartCommand will be sent!
        context.startService(svc);
        // Update should be started before falling into sleep.
        // Should we acquire wakelock here?
        // LifeSupportService.getWakeLock();
    }

    /**
     * NOTE
     * Next updates which are at least 1-min after, will be scheduled.
     */
    static void
    scheduleNextUpdate(Calendar calNow) {
        long daybase = Util.dayBaseMs(calNow);
        long dayms = calNow.getTimeInMillis() - daybase;
        if (dayms < 0)
            dayms = 0; // To compensate 1 sec error from '/' operation.
        P.bug(dayms <= Util.DAY_IN_MS);

        // If we get killed, after returning from here, restart
        Cursor c = DBPolicy.get().queryChannel(ColumnChannel.SCHEDUPDATETIME);
        if (!c.moveToFirst()) {
            c.close();
            return; // There is no channel.
        }

        final long invalidNearestNext = Util.DAY_IN_MS * 2;
        long nearestNext = invalidNearestNext; // large enough value.
        do {
            String sStr = c.getString(0);
            if (Util.isValidValue(sStr)) {
                // NOTE : IMPORTANT
                //   Time stored at DB is HOUR_OF_DAY (0 - 23)
                //   (See comments regarding Column at DB.)
                //   We cannot guarantee that service is started at exact time.
                //   So, we should compensate it (see comments at 'onStartCommand'
                long[] secs   = Util.nStringToNrs(sStr);
                long[] out  = new long[2];
                for (long s : secs) {
                    long sms = Util.secToMs(s);
                    dayBasedDistanceMs(out, dayms, sms);

                    // out[0] is time to go from 'dayms' to 'hms'
                    if (out[0] < nearestNext)
                        nearestNext = out[0];
                }
            }
        } while (c.moveToNext());
        c.close();

        if (nearestNext != invalidNearestNext) {
            Context cxt = AppEnv.getAppContext();
            // convert into real time.
            nearestNext += calNow.getTimeInMillis();
            Intent intent = new Intent(cxt, AlarmReceiver.class);
            intent.setAction(SCHEDUPDATE_INTENT_ACTION);
            intent.putExtra("time", nearestNext);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            PendingIntent pIntent = PendingIntent.getBroadcast(
                    cxt,
                    0,
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT);
            // Get the AlarmManager service
            AlarmManager am = (AlarmManager)cxt.getSystemService(ALARM_SERVICE);
            am.set(AlarmManager.RTC_WAKEUP, nearestNext, pIntent);
            if (DBG) P.v("New nearest scheduled update is set! " + (nearestNext / 1000) + " sec.");
        }
    }

    private void
    startUpdates(int startId, long[] cids) {
        for (long cid : cids) {
            // NOTE
            // onStartCommand() is run on UIThread.
            // So, I don't need to worry about race-condition caused from re-entrance of this function
            // That's why 'getState' and 'unregister/register' are not synchronized explicitly.
            UpdateTask t = mRtt.getUpdateTask(cid);
            switch (mRtt.getRtState(t)) {
            case READY:
            case RUN:
            case CANCEL:
                // Nothing to do
                break;
            default:
                if (null != t) {
                    P.bug(t.isDone());
                    mRtt.removeWatchedTask(t);
                }
                t = new UpdateTask(cid, null);
                if (mRtt.addTask(t, cid, RTTask.Action.UPDATE)) {
                    t.addEventListener(AppEnv.getUiHandlerAdapter(), mUpdateTaskListener);
                    mTaskCnt.incrementAndGet();
                } else if (DBG) P.w("doCmdAlarm : starts duplicated update! : " + cid);
            }
        }
    }

    private void
    doCmdResched(@SuppressWarnings("unused") int startId) {
        // Just reschedule and return.
        scheduleNextUpdate(Calendar.getInstance());
    }

    private void
    doCmdAlarm(int startId, long schedTime) {
        if (DBG) P.v("DO scheduled update!! : " + startId);
        Calendar calNow = Calendar.getInstance();
        long daybase = Util.dayBaseMs(calNow);
        long dayms = calNow.getTimeInMillis() - daybase;

        if (schedTime < 0 // something wrong!!
            || calNow.getTimeInMillis() < schedTime // scheduled too early
            || calNow.getTimeInMillis() > schedTime + Util.HOUR_IN_MS) { // scheduled too late
            if (DBG) P.w("WARN : weired scheduling!!!\n" +
                         "    scheduled time(ms) : " + schedTime + "\n" +
                         "    current time(ms)   : " + calNow.getTimeInMillis());
            scheduleNextUpdate(calNow);
            return;
        }

        // If we get killed, after returning from here, restart
        Cursor c = mDbp.queryChannel(new ColumnChannel[] {
                ColumnChannel.ID,
                ColumnChannel.SCHEDUPDATETIME });
        // below values are 'Column Index' for above query.
        final int iId = 0;
        final int iTime = 1;

        if (!c.moveToFirst()) {
            c.close();
            return; // There is no channel.
        }

        // NOTE : IMPORTANT
        // Service is started behind it's original plan by amount of 'schedError'
        // So, service should run scheduled-update whose planed-sched-time is
        //   between 'schedTime' and 'current'.
        long schedError = calNow.getTimeInMillis() - schedTime;
        LinkedList<Long> chl = new LinkedList<>();
        do {
            String sStr = c.getString(iTime);
            //   We cannot guarantee that service is started at exact time.
            //   So, we need to check error and find next scheduled based on this error.
            long[] ss  = Util.nStringToNrs(sStr);
            long   cid = c.getLong(iId);
            long[] out = new long[2];
            for (long s : ss) {
                long sms = Util.secToMs(s);
                dayBasedDistanceMs(out, dayms, sms);

                // out[1] is time to go from 'hms' to 'dayms'
                // This means 'time passed since 'hms' because 'dayms' is current time.
                if (out[1] <= schedError)
                    chl.add(cid);
            }
        } while (c.moveToNext());
        c.close();

        long[] cids = convertArrayLongTolong(chl.toArray(new Long[chl.size()]));
        startUpdates(startId, cids);

        // register next scheduled-update.
        // NOTE
        //   Real-Now - Calendar.getInstance() - SHOULD NOT be used here!
        //   We already have error between 'calNow' and Real-Now
        //     (we already spends some time to run some code!)
        //   But, some channels may be scheduled between these two time - 'calNow' and Real-Now.
        //   If Real-Now is chosen as current calendar, tasks mentioned at above line, is missed from scheduled-update!
        //   So, 'calNow' should be used as current calendar!
        scheduleNextUpdate(calNow);
    }


    @SuppressWarnings("StringConcatenationInsideStringBufferAppend")
    private void
    doCmdUpdate(int startId, long[] cids) {
        if (DBG) {
            StringBuilder bldr = new StringBuilder("");
            bldr.append("DO Command update!! : " + startId + "\n")
                .append("Cids: ");
            for (long cid : cids)
                bldr.append(cid + ", ");
            bldr.append("\n");
            P.v(bldr.toString());
        }

        startUpdates(startId, cids);
    }

    private void
    runStartCommand(Intent intent,
                    @SuppressWarnings("unused") int flags,
                    int startId) {
        String cmd = intent.getStringExtra(KEY_CMD);
        //logI("ScheduledUpdate : runStartCommand : " + cmd);
        // 'cmd' can be null.
        // So, DO NOT use "cmd.equals()"...
        if (CMD_RESCHED.equals(cmd))
            doCmdResched(startId);
        else if (CMD_ALARM.equals(cmd)) {
            long schedTime = intent.getLongExtra(KEY_TIME, -1);
            doCmdAlarm(startId, schedTime);
        } else if (CMD_UPDATE.equals(cmd)) {
            long[] cids = intent.getLongArrayExtra(KEY_CHANS);
            doCmdUpdate(startId, cids);
        } else
            P.bug(false);

        P.bug(mTaskCnt.get() >= 0);
        if (0 >= mTaskCnt.get())
            stopSelf();
    }

    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lv) {
        return "[ ScheduledUpdateService ]";
    }

    @Override
    public void
    onCreate() {
        super.onCreate();
        if (!Environ.get().hasEssentialPermissions()) {
            UxUtil.showTextToast(R.string.err_essential_perm);
            stopSelf();
            return;
        }
        UnexpectedExceptionHandler.get().registerModule(this);
        incInstanceCount();
    }

    // NOTE:
    //   Starting service requires getting WakeLock!
    //   onStartCommand is run on main ui thread (same as onReceive).
    //   So, we don't need to concern about race-condition between these two.
    @Override
    public int
    onStartCommand(Intent intent, int flags, int startId) {
        try {
            // try after some time again.
            if (!ScheduledUpdateService.isEnabled())
                AppEnv.getUiHandler().postDelayed(new StartCmdPost(intent, flags, startId), RETRY_DELAY);
            else
                runStartCommand(intent, flags, startId);
        } finally {
            // At any case wakelock should be released.
            // Task itself will manage wakelock for the background tasking.
            // We don't need to worry about update jobs.
            // Just release wakelock for this command.
            // Wakelock will be handled by LifeSupportService.
            // LifeSupportService.putWakeLock();
            ;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder
    onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }

    @Override
    public void
    onDestroy() {
        decInstanceCount();
        UnexpectedExceptionHandler.get().unregisterModule(this);
        //logI("ScheduledUpdateService : onDestroy");
        super.onDestroy();
    }
}
