package free.yhc.feeder;

import static free.yhc.feeder.model.Utils.DBG;
import android.os.Parcelable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.view.ViewGroup;
import free.yhc.feeder.model.DBPolicy;
import free.yhc.feeder.model.Feed;
import free.yhc.feeder.model.Utils;

public class ChannelListPagerAdapter extends FragmentPagerAdapter {
    private static final Utils.Logger P = new Utils.Logger(ChannelListPagerAdapter.class);

    private final DBPolicy          mDbp = DBPolicy.get();
    private long[]                  mCatIds;
    private ChannelListFragment[]   mFragments;
    private int                     mActive = -1;

    private void
    reset(Feed.Category[] cats) {
        long[] catIds = new long[cats.length];
        for (int i = 0; i < catIds.length; i++)
            catIds[i] = cats[i].id;
        reset(catIds);
    }

    private void
    reset(long[] catIds) {
        mCatIds = catIds;
        mFragments = new ChannelListFragment[catIds.length];
        for (int i = 0; i < mFragments.length; i++)
            mFragments[i] = null;
    }

    public ChannelListPagerAdapter(FragmentManager fm,
                                   Feed.Category[] cats) {
        super(fm);
        reset(cats);
    }

    public int
    getPosition(long catId) {
        for (int i = 0; i < mCatIds.length; i++) {
            if (mCatIds[i] == catId)
                return i;
        }
        return -1;
    }

    public void
    refreshDataSet() {
        if (DBG) P.v("refreshDataSet");
        reset(mDbp.getCategories());
        super.notifyDataSetChanged();
    }

    public void
    newCategoryAdded(long newCatId) {
        if (DBG) P.v("newCategoryAdded : " + newCatId);
        long[] newCatIds = new long[mCatIds.length + 1];
        System.arraycopy(mCatIds, 0, newCatIds, 0, mCatIds.length);
        newCatIds[mCatIds.length] = newCatId;
        ChannelListFragment[] newFragments = new ChannelListFragment[newCatIds.length];
        System.arraycopy(mFragments, 0, newFragments, 0, mFragments.length);
        newFragments[mFragments.length] = null;
        mCatIds = newCatIds;
        mFragments = newFragments;
        super.notifyDataSetChanged();
    }

    public void
    categoryDeleted(long catId) {
        if (DBG) P.v("categoryDeleted : " + catId);
        long[] newCatIds = new long[mCatIds.length - 1];
        ChannelListFragment[] newFragments = new ChannelListFragment[newCatIds.length];
        try {
            int j = 0;
            for (int i = 0; i < mCatIds.length; i++) {
                if (mCatIds[i] != catId) {
                    newCatIds[j] = mCatIds[i];
                    newFragments[j++] = mFragments[i];
                }
            }
            mCatIds = newCatIds;
            mFragments = newFragments;
            super.notifyDataSetChanged();
        } catch (ArrayIndexOutOfBoundsException ignored) { }
    }

    public ChannelListFragment
    getFragment(long catId) {
        int i = getPosition(catId);
        if (i < 0)
            return null;

        return mFragments[i];
    }

    @Override
    public void
    setPrimaryItem(ViewGroup container, int position, Object object) {
        int oldi = mActive;
        int newi = position;
        if (oldi != newi) {
            try {
                if (null != mFragments[oldi])
                    mFragments[oldi].setToActive(false);
            } catch (ArrayIndexOutOfBoundsException ignored) { }
            mActive = newi;
            try {
                if (null != mFragments[newi])
                    mFragments[newi].setToActive(true);
            } catch (ArrayIndexOutOfBoundsException ignored) { }
        }
        super.setPrimaryItem(container, position, object);
    }

    @Override
    public int
    getCount() {
        return mCatIds.length;
    }

    @Override
    public Fragment
    getItem(int position) {
        if (DBG) P.v("getItem : " + position);
        if (null == mFragments[position])
            mFragments[position] = ChannelListFragment.newInstance(this, mCatIds[position]);

        mFragments[position].setToActive(mActive == position);
        return mFragments[position];
    }

    @Override
    public long
    getItemId(int position) {
        return mCatIds[position];
    }

    @Override
    public Parcelable
    saveState() {
        if (DBG) P.v("saveState()");
        return super.saveState();
    }

    @Override
    public void
    restoreState(Parcelable state, ClassLoader loader) {
        if (DBG) P.v("restoreState");
        super.restoreState(state, loader);
    }

    @Override
    public void
    startUpdate(ViewGroup container) {
        //if (DBG) P.v("startUpdate");
        super.startUpdate(container);
    }

    @Override
    public void
    finishUpdate(ViewGroup container) {
        //if (DBG) P.v("finishUpdate");
        super.finishUpdate(container);
    }

    @Override
    public Object
    instantiateItem(ViewGroup container, int position) {
        if (DBG) P.v("instantiateItem : " + position);
        return super.instantiateItem(container, position);
    }

    @Override
    public void
    destroyItem(ViewGroup container, int position, Object object) {
        if (DBG) P.v("destroyItem : " + position);
        super.destroyItem(container, position, object);
    }

}
