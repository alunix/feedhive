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
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.View;
import free.yhc.feeder.model.Environ;
import free.yhc.feeder.model.Err;
import free.yhc.feeder.model.ThreadEx;
import free.yhc.feeder.model.UnexpectedExceptionHandler;
import free.yhc.feeder.model.Utils;

public class DiagAsyncTask extends ThreadEx<Err> implements
UnexpectedExceptionHandler.TrackedModule,
DialogInterface.OnDismissListener,
View.OnClickListener {
    private static final boolean DBG = false;
    private static final Utils.Logger P = new Utils.Logger(DiagAsyncTask.class);

    private Context         mContext        = null;
    private ProgressDialog  mDialog         = null;
    private CharSequence    mTitle          = null;
    private CharSequence    mMessage        = null;
    private Style           mStyle          = Style.SPIN;
    private Worker          mWorker         = null;
    private boolean         mUserCancelled  = false;
    private boolean         mCancelable     = true;
    private boolean         mInterruptOnCancel = true;
    private DialogInterface.OnDismissListener mOnDismissListener = null;

    public static abstract class Worker {
        public abstract Err
        doBackgroundWork(DiagAsyncTask task);

        public void
        onPreExecute(DiagAsyncTask task) { }

        public void
        onPostExecute(DiagAsyncTask task, Err result) { }

        public void
        onCancel(DiagAsyncTask task) { }

        public void
        onCancelled(DiagAsyncTask task) { }
    }

    public static enum Style {
        SPIN        (ProgressDialog.STYLE_SPINNER),
        PROGRESS    (ProgressDialog.STYLE_HORIZONTAL);

        private int style;

        Style(int aStyle) {
            style = aStyle;
        }

        public int
        getStyle() {
            return style;
        }
    }

    public DiagAsyncTask(Context context,
                         Worker listener,
                         Style style,
                         CharSequence msg,
                         boolean cancelable,
                         boolean interruptOnCancel) {
        super();
        mContext= context;
        mWorker = listener;
        mMessage = msg;
        mStyle  = style;
        mCancelable = cancelable;
        mInterruptOnCancel = interruptOnCancel;
    }

    public DiagAsyncTask(Context context,
            Worker listener,
            Style style,
            int msg,
            boolean cancelable,
            boolean interruptOnCancel) {
        this(context, listener, style, context.getResources().getText(msg), cancelable, interruptOnCancel);
    }

    public DiagAsyncTask(Context context,
                         Worker listener,
                         Style style,
                         CharSequence msg,
                         boolean cancelable) {
        this(context, listener, style, msg, cancelable, true);
    }

    public DiagAsyncTask(Context context,
            Worker listener,
            Style style,
            int msg,
            boolean cancelable) {
        this(context, listener, style, context.getResources().getText(msg), cancelable, true);
    }

    public DiagAsyncTask(Context context,
                         Worker listener,
                         Style style,
                         CharSequence msg) {
        this(context, listener, style, msg, false, true);
    }

    public DiagAsyncTask(Context context,
            Worker listener,
            Style style,
            int msg) {
        this(context, listener, style, context.getResources().getText(msg), false, true);
    }

    public void
    setOnDismissListener(DialogInterface.OnDismissListener listener) {
        eAssert(Utils.isUiThread());
        mOnDismissListener = listener;
    }

    public void
    setTitle(CharSequence title) {
        mTitle = title;
    }

    public void
    setTitle(int title) {
        setTitle(mContext.getResources().getText(title));
    }

    public void
    publishMessage(final String message) {
        Environ.getUiHandler().post(new Runnable() {
            @Override
            public void
            run() {
                mDialog.setMessage(message);
            }
        });
    }

    public boolean
    userCancel() {
        if (!mCancelable || mUserCancelled)
            return false;

        mUserCancelled = true;
        mDialog.setMessage(mContext.getResources().getText(R.string.wait_cancel_msg));
        return super.cancel(mInterruptOnCancel);
    }

    /**
     * This is used usually to avoid "leaked window..." error.
     * But be careful to use it.
     * This function dismiss ONLY dialog and doens't cancel background job!
     */
    public void
    forceDismissDialog() {
        if (null != mDialog)
            mDialog.dismiss();
    }

    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lvl) {
        return "[ SpinAsyncTask : " + getName() + " ]";
    }

    @Override
    protected Err
    doAsyncTask() {
        //logI("* Start background Job : SpinSyncTask\n");
        try {
            Err ret = Err.NO_ERR;
            if (null != mWorker)
                ret = mWorker.doBackgroundWork(this);
            return ret;
        } catch (Throwable e) {
            return Err.UNKNOWN;
        }
    }

    @Override
    public void
    onProgress(int percent) {
        if (null == mDialog)
            return;

        mDialog.setProgress(percent);
    }

    @Override
    public void
    onCancelled() {
        if (null != mWorker)
            mWorker.onCancelled(this);

        mDialog.dismiss();
    }

    @Override
    protected void
    onPostRun(Err result) {
        // In normal case, onPostExecute is not called in case of 'user-cancel'.
        // below code is for safety.
        if (!mUserCancelled && null != mWorker)
            mWorker.onPostExecute(this, result);

        mDialog.dismiss();
    }

    @Override
    protected void
    onCancel() {
        if (null != mWorker)
            mWorker.onCancel(this);
    }

    @Override
    protected void
    onPreRun() {
        mDialog = new ProgressDialog(mContext);
        if (null != mTitle)
            mDialog.setTitle(mTitle);
        if (null != mMessage)
            mDialog.setMessage(mMessage);
        mDialog.setProgressStyle(mStyle.getStyle());
        mDialog.setMax(100); // percent
        // To prevent dialog is dismissed unexpectedly by back-key
        mDialog.setCancelable(false);

        // To prevent dialog is dismissed unexpectedly by search-key (in Gingerbread)
        mDialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean
            onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                if (KeyEvent.KEYCODE_SEARCH == keyCode
                    && 0 == event.getRepeatCount()) {
                    return true;
                }
                return false;
            }
        });

        mDialog.setOnDismissListener(this);

        // NOTE
        // See below codes.
        // In case of cancelable dialog, set dummy onClick is registered.
        // And then, REAL onClick is re-registered for the button directly.
        // This is to workaround Android Framework's ProgressDialog policy.
        // According to Android Framework, ProgressDialog dismissed as soon as button is clicked.
        // But, this is not what I expected.
        // Below code is a way of workaround this policy.
        // For details, See "https://groups.google.com/forum/?fromgroups=#!topic/android-developers/-1bIchuFASQ".
        if (mCancelable) {
            // Set dummy onClick listener.
            mDialog.setButton(Dialog.BUTTON_POSITIVE,
                              mContext.getResources().getText(R.string.cancel),
                              new DialogInterface.OnClickListener() {
                                  @Override
                                  public void
                                  onClick(DialogInterface dialog, int which) {
                                  }
                              });
        }
        mDialog.show();
        if (mCancelable)
            mDialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(this);

        if (null != mWorker)
            mWorker.onPreExecute(this);
    }

    /**
     * handle event - cancel button is clicked.
     */
    @Override
    public void
    onClick(View view) {
        userCancel();
    }

    @Override
    public void
    onDismiss(DialogInterface dialogI) {
        if (null != mOnDismissListener)
            mOnDismissListener.onDismiss(dialogI);
    }

    @Override
    protected void
    finalize() throws Throwable {
        super.finalize();
    }
}