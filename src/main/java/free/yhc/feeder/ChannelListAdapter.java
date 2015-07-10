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

import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;

import android.content.Context;
import android.database.Cursor;
import android.database.StaleDataException;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import free.yhc.feeder.db.ColumnChannel;
import free.yhc.feeder.db.DBPolicy;
import free.yhc.feeder.model.RTTask;
import free.yhc.feeder.model.UnexpectedExceptionHandler;
import free.yhc.feeder.model.Utils;

public class ChannelListAdapter extends AsyncCursorListAdapter implements
AsyncCursorAdapter.ItemBuilder {
    private static final boolean DBG = false;
    private static final Utils.Logger P = new Utils.Logger(ChannelListAdapter.class);

    private static final Date sDummyDate = new Date();
    private static final ColumnChannel[] sChannQueryColumns = new ColumnChannel[] {
        ColumnChannel.ID, // Mandatory.
        ColumnChannel.URL,
        ColumnChannel.TITLE,
        ColumnChannel.DESCRIPTION,
        ColumnChannel.LASTUPDATE,
        ColumnChannel.URL
    };

    private final DBPolicy mDbp = DBPolicy.get();
    private final RTTask   mRtt = RTTask.get();

    private final OnActionListener     mActionListener;
    private final View.OnClickListener mChIconOnClick;
    private final View.OnClickListener mPosUpOnClick;
    private final View.OnClickListener mPosDnOnClick;

    private final HashMap<View, Integer> mView2PosMap = new HashMap<View, Integer>();

    interface OnActionListener {
        void onUpdateClick(ImageView ibtn, long cid);
        void onMoveUpClick(ImageView ibtn, long cid);
        void onMoveDownClick(ImageView ibtn, long cid);
    }

    private static class ItemInfo {
        long        cid             = -1;
        String      url             = "";
        String      title           = "";
        String      desc            = "";
        Date        lastUpdate      = sDummyDate;
        long        maxItemId       = 0;
        long        oldLastItemId   = 0;
        Bitmap      bm              = null;
    }

    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lv) {
        return super.dump(lv) + "[ ChannelListAdapter ]";
    }

    ChannelListAdapter(Context          context,
                       Cursor           cursor,
                       ListView         lv,
                       final int        dataReqSz,
                       final int        maxArrSz,
                       OnActionListener listener) {
        super(context,
              cursor,
              null,
              R.layout.channel_row,
              lv,
              new ItemInfo(),
              dataReqSz,
              maxArrSz,
              false);
        setItemBuilder(this);
        mActionListener = listener;

        mChIconOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (null == mActionListener)
                    return;
                mActionListener.onUpdateClick((ImageView)v, (long)(Long)v.getTag());
            }
        };

        mPosUpOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (null == mActionListener)
                    return;
                mActionListener.onMoveUpClick((ImageView)v, (long)(Long)v.getTag());
            }
        };

        mPosDnOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (null == mActionListener)
                    return;
                mActionListener.onMoveDownClick((ImageView)v, (long)(Long)v.getTag());
            }
        };
    }

    public static Cursor
    getQueryCursor(long catId) {
        return DBPolicy.get().queryChannel(catId, sChannQueryColumns);
    }

    public int
    findPosition(long cid) {
        eAssert(Utils.isUiThread());
        for (int i = 0; i < getCount(); i++) {
            if (getItemInfo_cid(i) == cid)
                    return i;
        }
        return -1;
    }

    public int
    findItemId(long cid) {
        int pos = findPosition(cid);
        if (pos < 0)
            return -1;
        else
            return (int)getItemId(pos);
    }

    /**
     * Data is NOT reloaded.
     * Only item array is changed.
     * @param pos0
     * @param pos1
     */
    public void
    switchPos(int pos0, int pos1) {
        eAssert(Utils.isUiThread());
        Object sv = setItem(pos0, getItem(pos1));
        eAssert(null != sv);
        setItem(pos1, sv);
        notifyDataSetChanged();
    }

    /**
     * Data is NOT reloaded.
     * Only item array is changed.
     * @param pos0
     * @param pos1
     */
    public void
    notifyChannelIconChanged(long cid) {
        eAssert(Utils.isUiThread());
        ItemInfo ii = (ItemInfo)getItem(findPosition(cid));
        ii.bm = mDbp.getChannelImageBitmap(cid);
        notifyDataSetChanged();
    }

    public void
    removeChannel(long cid) {
        int pos = findPosition(cid);
        removeItem(pos);
    }

    public void
    appendChannel(long cid) {
        Cursor c = DBPolicy.get().queryChannel(sChannQueryColumns, ColumnChannel.ID, cid);
        c.moveToFirst();
        Object item = buildItem(this, c);
        c.close();
        insertItem(getCount(), item);
    }

    public long
    getItemInfo_cid(int position) {
        return ((ItemInfo)super.getItem(position)).cid;
    }

    /**
     * rebind view of given cid only.
     * @param cid
     */
    public void
    notifyChannelRttStateChanged(long cid) {
        int pos = findPosition(cid);
        if (pos < 0)
            return; // Not an visible channel
        for (View v : mView2PosMap.keySet().toArray(new View[0])) {
            if (pos == mView2PosMap.get(v)) {
                doBindView(v, (ItemInfo)getItem(pos));
                return;
            }
        }
    }

    @Override
    public Object
    buildItem(AsyncCursorAdapter adapter, Cursor c) {
        //logI("ChannelListAdapter : buildItem - START");
        ItemInfo i = new ItemInfo();
        try {
            i.cid = getCursorLong(c, ColumnChannel.ID);
            i.url = getCursorString(c, ColumnChannel.URL);
            i.title = getCursorString(c, ColumnChannel.TITLE);
            i.desc = getCursorString(c, ColumnChannel.DESCRIPTION);
            i.lastUpdate = new Date(getCursorLong(c, ColumnChannel.LASTUPDATE));
            i.maxItemId = mDbp.getItemInfoMaxId(i.cid);
            i.oldLastItemId = mDbp.getChannelInfoLong(i.cid, ColumnChannel.OLDLAST_ITEMID);
            i.bm = mDbp.getChannelImageBitmap(i.cid);
        } catch (StaleDataException e) {
            eAssert(false);
        }
        //logI("ChannelListAdapter : buildItem - END");
        return i;
    }

    @Override
    public void
    destroyItem(AsyncCursorAdapter adapter, Object item) {
        ItemInfo ii = (ItemInfo)item;
        // DO NOT recycle bitmap here!
        // bitmap is already cached!
        ii.bm = null;
    }

    @Override
    public int
    requestData(final AsyncAdapter adapter, Object priv, long nrseq, final int from, final int sz) {
        // Override to use "delayed item update"
        int ret;
        try {
            mDbp.getDelayedChannelUpdate();
            ret = super.requestData(adapter, priv, nrseq, from, sz);
        } finally {
            mDbp.putDelayedChannelUpdate();
        }
        return ret;
    }

    private void
    doBindView(View v, ItemInfo ii) {
        long nrNew = ii.maxItemId - ii.oldLastItemId;

        ImageView chIcon = (ImageView)v.findViewById(R.id.image);
        chIcon.setTag(ii.cid);
        chIcon.setOnClickListener(mChIconOnClick);

        ImageView ibtn = (ImageView)v.findViewById(R.id.imgup);
        ibtn.setTag(ii.cid);
        ibtn.setOnClickListener(mPosUpOnClick);

        ibtn = (ImageView)v.findViewById(R.id.imgdown);
        ibtn.setTag(ii.cid);
        ibtn.setOnClickListener(mPosDnOnClick);

        if (null == ii.bm)
            // fail to decode.
            chIcon.setImageResource(R.drawable.ic_warn_image);
        else
            chIcon.setImageBitmap(ii.bm);

        ImageView noti_up = (ImageView)v.findViewById(R.id.noti_update);
        ImageView noti_dn = (ImageView)v.findViewById(R.id.noti_download);

        RTTask.TaskState state = mRtt.getState(ii.cid, RTTask.Action.UPDATE);
        noti_up.setVisibility(View.VISIBLE);
        switch(state) {
        case IDLE:
            noti_up.setVisibility(View.GONE);
            break;

        case READY:
            noti_up.setImageResource(R.drawable.ic_pause);
            break;

        case RUNNING:
            noti_up.setImageResource(R.drawable.ic_refresh);
            break;

        case CANCELING:
            noti_up.setImageResource(R.drawable.ic_block);
            break;

        case FAILED:
            noti_up.setImageResource(R.drawable.ic_info);
            break;

        default:
            eAssert(false);
        }

        if (0 == mRtt.getItemsDownloading(ii.cid).length)
            noti_dn.setVisibility(View.GONE);
        else
            noti_dn.setVisibility(View.VISIBLE);

        String date = DateFormat.getInstance().format(ii.lastUpdate);
        // === Set 'age' ===
        // calculate age and convert to readable string.
        String age;
        { // just for temporal variable scope
            long ageTime = new Date().getTime() - ii.lastUpdate.getTime();
            // Show "day:hours"
            long ageHours = ageTime/ (1000 * 60 * 60);
            long ageDay = ageHours / 24;
            ageHours %= 24;
            age = String.format("%2d:%2d", ageDay, ageHours);
        }

        // If there is NO valid title for this channel, Just use URL as title.
        String title = Utils.isValidValue(ii.title)? ii.title: ii.url;
        ((TextView)v.findViewById(R.id.title)).setText(title);
        ((TextView)v.findViewById(R.id.description)).setText(ii.desc);
        ((TextView)v.findViewById(R.id.date)).setText(date);
        ((TextView)v.findViewById(R.id.age)).setText(age);
        ImageView msgImage = ((ImageView)v.findViewById(R.id.msg_img));
        if (nrNew > 0)
            msgImage.setVisibility(View.VISIBLE);
        else
            msgImage.setVisibility(View.GONE);
    }

    @Override
    protected void
    bindView(View v, final Context context, int position)  {
        if (DBG) P.v("Position : " + position);
        if (!preBindView(v, context, position))
            return;

        mView2PosMap.put(v, position);
        doBindView(v, (ItemInfo)getItem(position));
    }
}
