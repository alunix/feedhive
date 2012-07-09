package free.yhc.feeder;

import static free.yhc.feeder.model.Utils.eAssert;
import android.content.Context;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import free.yhc.feeder.model.Err;
import free.yhc.feeder.model.UnexpectedExceptionHandler;
import free.yhc.feeder.model.Utils;

public class AsyncAdapter extends BaseAdapter implements
UnexpectedExceptionHandler.TrackedModule {
    // Variables to store information - not changed in dynamic
    protected final Context       context;
    protected final Handler       uiHandler = new Handler();
    private   final ListView      lv;
    private         DataProvider  dp        = null;
    private         OnRequestData onRD      = null;
    private   final int           dataReqSz;
    private   final int           maxArrSz; // max array size of items
    private   final int           rowLayout;
    private   final Object        dummyItem;
    private   final View          firstDummyView;
    private   final int           firstLDahead;


    // Variables those are changed dynamically
    // NOTE!
    // Below variables are used(RW) by multiple threads.
    // But, any synchronization is not used for them. Why?
    // By using 'dpDone' flag, below variables are free from race-condition.
    // Please check & confirm this.
    // (If not, I SHOULD USE LOCK for synchronization!)

    // position of top item
    // this is position of item located at item array[0].
    private   int           posTop      = 0;

    // NOTE
    // Accessed only in UI Thread Context!
    // real-data-count.
    // This is decided when provider set 'eod - End Of Data' flag.
    private   int           dataCnt     = -1;

    // NOTE
    // variable 'items' SHOULD BE MODIFIED ONLY ON UI THREAD CONTEXT!!!
    protected Object[]      items;

    // This is used only on UI Thread context.
    // So, this is not needed to be 'volatile'
    private   int           nrseq       = 0;

    // For synchronization
    // Read/Write operation to java primitive/reference is atomic!
    // So, use with 'volatile'
    private volatile boolean dpDone     = false;
    private SpinAsyncTask    dpTask     = null;

    /**
     * provide data to this adapter asynchronously
     */
    interface DataProvider {
        /**
         * NOTE
         * At this function, item array of this adapter may be changed (even can be shrink).
         * And this function may be called at multiple thread.
         * So, function should be "MULTI-THREAD SAFE"
         * Or, thread may be interrupted in the middle of running.
         * @param adapter
         * @param nrseq
         *   request sequence number
         * @param from
         * @param sz
         * @return
         *   return value is not used yet. it is just reserved.
         */
        int requestData(AsyncAdapter adapter, Object priv, long nrseq, int from, int sz);

        int requestDataCnt(AsyncAdapter adapter);
        /**
         * Let data provider know that item will not be used anymore.
         * Data provider may do some operation to prevent resource leak for the item
         * This callback will be called at main UI thread context.
         * @param adapter
         * @param items
         */
        void destroyData(AsyncAdapter adapter, Object data);
    }

    interface OnRequestData {
        /**
         * called when requesting data on UI Thread context
         * @param adapter
         * @param nrseq
         * @param from
         * @param sz
         */
        void onRequestData(AsyncAdapter adapter, long nrseq, int from, int sz);
        /**
         * called after data is provided and before notifying dataSetChanged on UI Thread context
         * @param adapter
         * @param nrseq
         * @param from
         * @param sz
         */
        void onDataProvided(AsyncAdapter adapter, long nrseq, int from, int sz);

    }

    private enum LDType {
        INIT,
        NEXT,
        PREV,
        RELOAD
    }

    /**
     * SHOULD BE created on UI Thread Context!
     * @param context
     * @param rowLayout
     * @param lv
     * @param dummyItem
     *   {@link DataProvider#destroyData(AsyncAdapter, Object)} will not be called for dummyItem.
     * @param dataReqSz
     * @param maxArrSz
     */
    protected
    AsyncAdapter(Context        context,
                 int            rowLayout,
                 ListView       lv,
                 Object         dummyItem, // dummy item for first load
                 final int      dataReqSz,
                 final int      maxArrSz) {
        eAssert(dataReqSz < maxArrSz);
        UnexpectedExceptionHandler.S().registerModule(this);

        this.context = context;
        this.rowLayout = rowLayout;
        this.lv = lv;
        this.dummyItem = dummyItem;
        this.dataReqSz = dataReqSz;
        this.maxArrSz = maxArrSz;
        // NOTE
        // This is policy.
        // When reload data, some of previous data would better to be loaded.
        // 1/3 of dataReqSz is reloaded together.
        this.firstLDahead = dataReqSz / 3;
        firstDummyView = new View(context);
        items = new Object[] { dummyItem };
    }

    protected boolean
    initalLoaded() {
        eAssert(Utils.isUiThread());
        return !(1 == items.length && items[0] == dummyItem);
    }

    protected void
    setDataProvider(DataProvider dp) {
        this.dp = dp;
    }

    protected void
    setRequestDataListener(OnRequestData listener) {
        this.onRD = listener;
    }

    /**
     * Should be run on main UI thread.
     * @param item
     */
    protected void
    destroyItem(Object item) {
        eAssert(Utils.isUiThread());
        if (dummyItem != item && null != item)
            dp.destroyData(this, item);
    }

    protected int
    getPosTop() {
        return posTop;
    }

    /**
     * return previous object
     */
    protected Object
    setItem(int pos, Object item) {
        eAssert(Utils.isUiThread());
        if (pos >= 0 && pos < items.length) {
            Object prev = items[pos];
            items[pos] = item;
            return prev;
        }
        return null;
    }

    /**
     *
     * @param pos
     *   position of this value is like below.
     *   +--------+---------+--------+---------+-----
     *   | pos[0] | item[0] | pos[1] | item[1] | ...
     *   +--------+---------+--------+---------+-----
     * @param item
     */
    protected void
    insertItem(int pos, Object item) {
        eAssert(Utils.isUiThread());
        eAssert(pos >= 0 && pos <= items.length);
        Object[] newItems = new Object[items.length + 1];
        System.arraycopy(items, 0, newItems, 0, pos);
        System.arraycopy(items, pos, newItems, pos + 1, items.length - pos);
        newItems[pos] = item;
        if (dataCnt > 0)
            dataCnt++;
        items = newItems;
    }

    /**
     * Remove item.
     * Item count is decreased by 1
     * @param pos
     */
    protected void
    removeItem(int pos) {
        eAssert(Utils.isUiThread());
        if (pos < 0 || pos >= items.length)
            // nothing to do
            return;
        Object[] newItems = new Object[items.length - 1];
        System.arraycopy(items, 0, newItems, 0, pos);
        System.arraycopy(items, pos + 1, newItems, pos, items.length - pos - 1);
        if (dataCnt > 0)
            dataCnt--;
        destroyItem(items[pos]);
        items = newItems;
    }

    /**
     *
     * @param ldtype
     * @param from
     *   meaningful only for LDType.RELOAD
     * @param sz
     * @return
     *   new items
     */
    private Object[]
    buildNewItemsArray(LDType ldtype, int from, int sz) {
        eAssert(Utils.isUiThread());
        eAssert(0 <= sz);
        Object[] newItems = null;

        if (LDType.INIT == ldtype || LDType.RELOAD == ldtype) {
            // new allocation.
            // Ignore all previous loading information.
            newItems = new Object[sz];
            posTop = from;
            for (Object o : items)
                destroyItem(o);
        } else if (LDType.NEXT == ldtype) {
            int sz2grow = sz;
            int sz2shrink = items.length + sz2grow - maxArrSz;
            sz2shrink = sz2shrink < 0? 0: sz2shrink;
            newItems = new Object[items.length + sz2grow - sz2shrink];
            System.arraycopy(items, sz2shrink, newItems, 0, items.length - sz2shrink);
            for (int i = 0; i < sz2shrink; i++)
                destroyItem(items[i]);
            posTop += sz2shrink;
        } else if (LDType.PREV == ldtype) {
            eAssert(0 < posTop && sz <= posTop);
            // After initial loading done in the middle of items
            int sz2grow = posTop;
            int sz2shrink = 0;
            sz2grow = sz2grow > sz? sz: sz2grow;
            sz2shrink = sz2grow + items.length - maxArrSz;
            sz2shrink = sz2shrink < 0? 0: sz2shrink;

            newItems = new Object[items.length + sz2grow - sz2shrink];
            System.arraycopy(items, 0, newItems, sz2grow, items.length - sz2shrink);
            for (int i = items.length - sz2shrink; i < items.length; i++)
                destroyItem(items[i]);
            posTop -= sz2grow;
        } else
            eAssert(false);
        return newItems;
    }

    private void
    waitDpDone(long reqSeq, int ms) {
        try {
            while (!dpDone && reqSeq == nrseq)
                Thread.sleep(ms);
        } catch (InterruptedException e) {}
    }

    private void
    requestDataAsync(final LDType ldtype, final int from, final int sz) {
        //logI("Data request UI : from " + from + ", # " + sz);

        eAssert(Utils.isUiThread());

        final long reqSeq = ++nrseq;
        dpDone = false;

        if (null != dpTask)
            dpTask.cancel(true);

        SpinAsyncTask.OnEvent bgRun = new SpinAsyncTask.OnEvent() {
            @Override
            public void onPostExecute(SpinAsyncTask task, Err result) {}
            @Override
            public Err
            onDoWork(SpinAsyncTask task, Object... objs) {
                //logI(">>> async request RUN - START: from " + from + ", # " + sz);
                dp.requestData(AsyncAdapter.this, ldtype, reqSeq, from, sz);
                waitDpDone(reqSeq, 50);
                //logI(">>> async request RUN - END: from " + from + ", # " + sz);
                return Err.NoErr;
            }
            @Override
            public void onCancel(SpinAsyncTask task) {}
        };
        dpTask = new SpinAsyncTask(context, bgRun, R.string.plz_wait, false);
        dpTask.setName("Asyn request : " + from + " #" + sz);
        if (null != onRD)
            onRD.onRequestData(this, reqSeq, from, sz);
        dpTask.execute(null, null);
    }

    /**
     * This function would better to be called mainly when
     *   - number of data is changed (decreased / increased).
     *   - lots of change in item order (ex. one of item in the middle is deleted etc)
     */
    public void
    reloadDataSetAsync() {
        eAssert(Utils.isUiThread());
        int from = posTop + lv.getFirstVisiblePosition() - firstLDahead;
        from = from < 0? 0: from;
        // dataset may be changed. So, reset dataCnt to 'unknown'
        dataCnt = -1;
        requestDataAsync(LDType.RELOAD, from, dataReqSz);
    }

    /**
     * This means "visible data range is changed."
     * So, after calling this function, reload should be called to apply.
     */
    public void
    moveToFirstDataSet() {
        eAssert(Utils.isUiThread());
        posTop = 0;
    }

    /**
     * This means "visible data range is changed."
     * So, after calling this function, reload should be called to apply.
     * And if new posTop is near data count - that is, there is too few items left to be shown
     *   at list, newly set top may be adjusted to smaller value. And it will be returned.
     * @param adataCnt
     *   number of real-data-count. This is larger than getCount() value which returns loaded data count.
     * @return
     *   changed posTop. This may different with given value.
     */
    public void
    moveToLastDataSet() {
        eAssert(Utils.isUiThread());
        dataCnt = dp.requestDataCnt(this);
        int newtop = dataCnt - dataReqSz + firstLDahead;
        posTop = (newtop < 0)? 0: newtop;
    }


    /**
     * @param priv
     *   Internal value passed by {@link AsyncAdapter}
     *   This value should be passed as it is.
     * @param reqSeq
     *   sequence number. (Given by adapter)
     *   This also should be passed as it is.
     * @param from
     * @param aitems
     * @param eod
     *   End Of Data
     *   If
     */
    public void
    provideItems(final Object priv, final long reqSeq, final int from, final Object[] aitems, final boolean eod) {
        //logI("AsyncAdapter provideItems - START : from " + from + ", # " + aitems.length);
        eAssert(maxArrSz > aitems.length);

        final LDType ldtype = (LDType)priv;
        // NOTE
        // Changing 'items' array SHOULD BE processed on UI Thread Context!!!
        // This is very important!
        // Why?
        // Most operations are accessing 'items' array on UI Thread Context.
        // So, to avoid race-condition!!
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                //logI("AsyncAdapter Provide Item Post Run (" + reqSeq + ", " + nrseq + ") - START");

                // Check that there is already new request or not.
                // This is run on UI thread. So, synchronization is not needed to be worried about.
                if (reqSeq != nrseq)
                    return;

                final Object[] newItems;
                if (LDType.INIT == ldtype || LDType.RELOAD == ldtype) {
                    newItems = buildNewItemsArray(ldtype, from, aitems.length);
                    System.arraycopy(aitems, 0, newItems, 0, aitems.length);
                } else if (from == posTop + items.length) {
                    newItems = buildNewItemsArray(LDType.NEXT, from, aitems.length);
                    System.arraycopy(aitems, 0, newItems, newItems.length - aitems.length, aitems.length);
                } else if (from == posTop - aitems.length) {
                    newItems = buildNewItemsArray(LDType.PREV, from, aitems.length);
                    System.arraycopy(aitems, 0, newItems, 0, aitems.length);
                } else if (from >= posTop && from + aitems.length <= posTop + items.length) {
                    // We don't need to re-allocate item array.
                    // Just reuse it!
                    System.arraycopy(aitems, 0, items, from - posTop, aitems.length);
                    newItems = items;
                } else {
                    eAssert(false);
                    newItems = null;
                }

                View v = lv.getChildAt(0);
                int posTopSv = posTop;
                int topY = (v == null) ? 0 : v.getTop();
                int firstVisiblePos = lv.getFirstVisiblePosition();

                items = newItems;

                if (eod)
                    dataCnt = posTop + items.length;

                // This is run on UI Thread.
                // So, we don't need to worry about race-condition.
                int posDelta = posTop - posTopSv;

                notifyDataSetChanged();
                // Restore list view's previous location.
                int pos = firstVisiblePos - posDelta;
                pos = pos < 0? 0: pos;
                if (0 == pos && 0 == posTop && topY < 0)
                    topY = 0; // we cannot before 'first item'
                lv.setSelectionFromTop(pos, topY);
                // check again at UI thread
                dpDone = true;
                dpTask = null;
                if (null != onRD)
                    onRD.onDataProvided(AsyncAdapter.this, reqSeq, from, aitems.length);
                //logI("AsyncAdapter Provide Item Post Run (" + reqSeq + ", " + nrseq + " - END");
            }
        });
        //logI("AsyncAdapter provideItems - END : from " + from + ", # " + aitems.length);
    }

    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lv) {
        return "[ AsyncAdapter ]"
                + "  dataCnt       : " + dataCnt + "\n"
                + "  items.length  : " + items.length + "\n"
                + "  posTop        : " + posTop + "\n";
    }

    /**
     * @return
     *   number of items that is loaded to array.(NOT real count.)
     */
    @Override
    public int getCount() {
        eAssert(Utils.isUiThread());
        //Log.i(TAG, ">>> getCount");
        return items.length;
    }

    @Override
    public Object
    getItem(int pos) {
        eAssert(Utils.isUiThread());
        //Log.i(TAG, ">>> getItem : " + position);
        if (pos < 0 || pos >= items.length)
            return null;
        return items[pos];
    }

    /**
     * item id is 'absolute' position of this item.
     * posTop + position
     */
    @Override
    public long
    getItemId(int position) {
        //Log.i(TAG, ">>> getItemId : " + position);
        return posTop + position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        //Log.i(TAG, ">>> getView : " + position);

        if (!initalLoaded()) {
            // reload some of previous item too.
            int from = posTop + lv.getFirstVisiblePosition() - firstLDahead;
            from = from < 0? 0: from;
            requestDataAsync(LDType.INIT, from, dataReqSz);
            return firstDummyView;
        }

        View v = convertView;
        if (null == convertView || convertView == firstDummyView) {
            LayoutInflater inflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            v = inflater.inflate(rowLayout, null);
        }

        bindView(v, context, position);

        // At bindView, functions like 'getItem' 'getItemId' may be used.
        // And these members are at risk of race-condition
        // To avoid this, below codes SHOULD be processed after bindView
        if (position == 0 && posTop > 0) {
            int szReq = (posTop > dataReqSz)? dataReqSz: posTop;
            // This is first item
            requestDataAsync(LDType.PREV, posTop - szReq, szReq);
        } else if (items.length - 1 == position
                   && (dataCnt < 0 || posTop + items.length < dataCnt)) {
            // This is last item
            requestDataAsync(LDType.NEXT, posTop + position + 1, dataReqSz);
        }
        return v;
    }

    protected void
    bindView(View v, final Context context, int position) {
        // FIXME
        // This is for test
        // ((TextView)v.findViewById(R.id.text)).setText((String)getItem(position));
    }

    @Override
    protected void
    finalize() throws Throwable {
        super.finalize();
        if (null != items) {
            for (Object o : items)
                destroyItem(o);
        }
        UnexpectedExceptionHandler.S().unregisterModule(this);
    }
}
