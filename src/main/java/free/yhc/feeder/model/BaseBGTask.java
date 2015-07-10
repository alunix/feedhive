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

package free.yhc.feeder.model;

import static free.yhc.feeder.model.Utils.eAssert;

import java.util.Iterator;
import java.util.LinkedList;

public abstract class BaseBGTask extends ThreadEx<Err> {
    private static final boolean DBG = false;
    private static final Utils.Logger P = new Utils.Logger(BaseBGTask.class);

    // Event if this is KeyBasedLinkedList, DO NOT USE KeyBasedLinkedList.
    // Using KeyBasedLinkedList here just increases code complexity.
    // Accessed only on 'Owner's thread context'.
    private final LinkedList<Elem> mEventListenerl = new LinkedList<Elem>();

    private Object  mCancelParam    = null;

    public static class OnEventListener {
        // return : false (DO NOT run this task)
        public void onPreRun       (BaseBGTask task) { }
        public void onPostRun      (BaseBGTask task, Err result) { }
        public void onCancel       (BaseBGTask task, Object param) { }
        public void onCancelled    (BaseBGTask task, Object param) { }
        public void onProgress     (BaseBGTask task, int progress) { }
    }

    private static class Elem {
        final Object            key;
        final OnEventListener   listener;
        Elem(Object aKey, OnEventListener aListener) {
            eAssert(null != aListener);
            key         = aKey;
            listener    = aListener;
        }
    }

    /**
     * This will be called prior to all other registered listeners
     */
    protected void
    onEarlyPreRun() {}

    /**
     * This will be called after all other registered listeners
     */
    protected void
    onLatePreRun() {}

    /**
     * This will be called prior to all other registered listeners
     */
    protected void
    onEarlyPostRun (Err result) {}

    /**
     * This will be called after all other registered listeners
     */
    protected void
    onLatePostRun (Err result) {}

    /**
     * This will be called prior to all other registered listeners
     */
    protected void
    onEarlyCancel(Object param) {}
    /**
     * This will be called after all other registered listeners
     */
    protected void
    onLateCancel(Object param) {}

    /**
     * This will be called prior to all other registered listeners
     */
    protected void
    onEarlyCancelled(Object param) {}

    /**
     * This will be called after all other registered listeners
     */
    protected void
    onLateCancelled(Object param) {}

    /**
     * Register event listener with it's key value.
     * Newly added event listener will be added to the last of listener list.
     * (event listener will be notified in order of listener list.)
     *
     * Key value is used to find event listener (onEvent).
     * Several event listener may share one key value.
     * Event callback will be called on owner thread message loop.
     * @param key
     * @param listener
     * @param hasPriority
     *   true if this event listener
     */
    void
    registerEventListener(Object key, OnEventListener listener, boolean hasPriority) {
        eAssert(isOwnerThread(Thread.currentThread()));
        Elem e = new Elem(key, listener);
        //logI("BGTask : registerEventListener : key(" + key + ") onEvent(" + onEvent + ")");
        if (hasPriority)
            mEventListenerl.addFirst(e);
        else
            mEventListenerl.addLast(e);
    }

    /**
     * Unregister event listener whose key and listener match.
     * one of 'key' and 'listener' can be null, but NOT both.
     * @param key
     *   'null' means ignore key value.
     *   otherwise listeners having matching key value, are unregistered.
     * @param listener
     *   'null' means unregister all listeners whose key value matches.
     *   otherwise, unregister listener whose key and listener both match.
     */
    void
    unregisterEventListener(Object key, OnEventListener listener) {
        eAssert(isOwnerThread(Thread.currentThread()));
        eAssert(null != key || null != listener);
        Iterator<Elem> iter = mEventListenerl.iterator();
        while (iter.hasNext()) {
            Elem e = iter.next();
            if ((null == key || e.key == key)
                && (null == listener || listener == e.listener)) {
                //logI("BGTask : unregisterEventListener : (" + listener.key + ") onEvent(" + listener.onEvent + ")");
                iter.remove();
            }
        }
    }

    BaseBGTask() {
    }
    /**
     * DANGEROUS FUNCTION
     * DO NOT USE if you are not sure what your are doing!
     * Make event listener list empty.
     * (Same as unregistering all listeners.)
     */
    void
    clearEventListener() {
        eAssert(isOwnerThread(Thread.currentThread()));
        //logI("BGTask : clearEventListener");
        mEventListenerl.clear();
    }

    boolean
    cancel(Object param) {
        mCancelParam = param;
        return cancel(true);
    }

    @Override
    protected void
    onPreRun() {
        onEarlyPreRun();
        Iterator<Elem> iter = mEventListenerl.iterator();
        while (iter.hasNext())
            iter.next().listener.onPreRun(this);
        onLatePreRun();
    }

    @Override
    protected void
    onPostRun(Err result) {
        onEarlyPostRun(result);
        Iterator<Elem> iter = mEventListenerl.iterator();
        while (iter.hasNext())
            iter.next().listener.onPostRun(this, result);
        onLatePostRun(result);
    }

    @Override
    protected void
    onCancel() {
        onEarlyCancel(mCancelParam);
        Iterator<Elem> iter = mEventListenerl.iterator();
        while (iter.hasNext())
            iter.next().listener.onCancel(this, mCancelParam);
        onLateCancel(mCancelParam);
    }

    @Override
    protected void
    onCancelled() {
        onEarlyCancelled(mCancelParam);
        Iterator<Elem> iter = mEventListenerl.iterator();
        while (iter.hasNext())
            iter.next().listener.onCancelled(this, mCancelParam);
        onLateCancelled(mCancelParam);
    }

    @Override
    protected void
    onProgress(int prog) {
        Iterator<Elem> iter = mEventListenerl.iterator();
        while (iter.hasNext())
            iter.next().listener.onProgress(this, prog);
    }
}
