/*****************************************************************************
 *    Copyright (C) 2012 Younghyung Cho. <yhcting77@gmail.com>
 *
 *    This file is part of Feeder.
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as
 *    published by the Free Software Foundation either version 3 of the
 *    License, or (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License
 *    (<http://www.gnu.org/licenses/lgpl.html>) for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *****************************************************************************/

package free.yhc.feeder;

import static free.yhc.feeder.model.Utils.eAssert;
import static free.yhc.feeder.model.Utils.logI;

import java.io.File;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import free.yhc.feeder.model.BGTask;
import free.yhc.feeder.model.BGTaskDownloadToFile;
import free.yhc.feeder.model.DB;
import free.yhc.feeder.model.DBPolicy;
import free.yhc.feeder.model.Err;
import free.yhc.feeder.model.RTTask;
import free.yhc.feeder.model.UIPolicy;
import free.yhc.feeder.model.UnexpectedExceptionHandler;

public class ItemViewActivity extends Activity implements
UnexpectedExceptionHandler.TrackedModule {
    private long    id  = -1;
    private String  netUrl = "";
    private String  fileUrl = "";
    private String  currUrl = "";
    private WebView wv  = null;
    private ProgressBar pb = null;

    private class WVClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            // Should NOT open at NEW window.
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            pb.setVisibility(View.GONE);
        }
    }

    private class WCClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            // Activities and WebViews measure progress with different scales.
            // The progress meter will automatically disappear when we reach 100%
            pb.setProgress(progress);
        }

        @Override
        public void onReachedMaxAppCacheSize(long spaceNeeded, long totalUsedQuota,
                                             WebStorage.QuotaUpdater quotaUpdater) {
            logI("ItemViewActivity : WebView : ReachedMaxAppCacheSize : " + spaceNeeded);
            quotaUpdater.updateQuota(spaceNeeded * 2);
        }
    }

    private class RTTaskManagerEventHandler implements RTTask.OnRTTaskManagerEvent {
        @Override
        public void
        onBGTaskRegister(long cid, BGTask task, RTTask.Action act) {
            if (RTTask.Action.Download == act)
                RTTask.S().bind(id, RTTask.Action.Download, this, new DownloadBGTaskOnEvent());
        }

        @Override
        public void onBGTaskUnregister(long cid, BGTask task, RTTask.Action act) { }
    }

    private class DownloadBGTaskOnEvent implements
    BGTask.OnEvent<BGTaskDownloadToFile.Arg, Object> {
        @Override
        public void
        onProgress(BGTask task, long progress) { }

        @Override
        public void
        onCancel(BGTask task, Object param) {
            postSetupLayout();
        }

        @Override
        public void
        onPreRun(BGTask task) {
            postSetupLayout();
        }

        @Override
        public void
        onPostRun(BGTask task, Err result) {
            logI("ItemViewActivity : DownloadToDBBGTaskOnEvent : onPostRun");
            postSetupLayout();
        }
    }

    private void
    startDownload() {
        BGTaskDownloadToFile dnTask
            = new BGTaskDownloadToFile(this, new BGTaskDownloadToFile.Arg(netUrl,
                                                                          UIPolicy.getItemDataFile(id),
                                                                          UIPolicy.getNewTempFile()));
        RTTask.S().register(id, RTTask.Action.Download, dnTask);
        RTTask.S().start(id, RTTask.Action.Download);
    }

    private void
    cancelDownload() {
        RTTask.S().cancel(id, RTTask.Action.Download, null);
    }

    private void
    notifyResult() {
        Err result = RTTask.S().getErr(id, RTTask.Action.Download);
        LookAndFeel.showTextToast(ItemViewActivity.this, result.getMsgId());
        RTTask.S().consumeResult(id, RTTask.Action.Download);
        setupLayout();
    }

    private void
    postSetupLayout() {
        wv.post(new Runnable() {
            @Override
            public void run() {
                setupLayout();
            }
         });
    }

    private void
    setupLayout() {
        ImageView imgbtn = (ImageView)findViewById(R.id.imgbtn);

        ItemViewActivity.this.findViewById(R.id.imgbtn).setVisibility(View.VISIBLE);
        Animation anim = imgbtn.getAnimation();
        if (null != anim) {
            anim.cancel();
            anim.reset();
        }
        imgbtn.setAlpha(0.5f);

        RTTask.TaskState state = RTTask.S().getState(id, RTTask.Action.Download);
        logI("ItemViewActivity : setupLayout : state : " + state.name());
        if (RTTask.TaskState.Idle == state) {

            if (currUrl.equals(fileUrl)) {
                imgbtn.setImageResource(R.drawable.ic_goto);
                imgbtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        currUrl = netUrl;
                        pb.setProgress(0);
                        pb.setVisibility(View.VISIBLE);
                        wv.loadUrl(currUrl);
                        postSetupLayout();
                    }
                });
            } else {
                if (UIPolicy.getItemDataFile(id).exists()) {
                    ItemViewActivity.this.findViewById(R.id.imgbtn).setVisibility(View.GONE);
                } else {
                    imgbtn.setImageResource(R.drawable.download_anim0);
                    imgbtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startDownload();
                            postSetupLayout();
                        }
                    });
                }
            }

        } else if (RTTask.TaskState.Running == state
                   || RTTask.TaskState.Ready == state) {

            imgbtn.setImageResource(R.drawable.download);
            ((AnimationDrawable)imgbtn.getDrawable()).start();
            imgbtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancelDownload();
                    postSetupLayout();
                }
            });

        } else if (RTTask.TaskState.Canceling == state) {

            imgbtn.setImageResource(R.drawable.ic_block);
            imgbtn.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_inout));
            imgbtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    LookAndFeel.showTextToast(ItemViewActivity.this, R.string.wait_cancel);
                }
            });

        } else if (RTTask.TaskState.Failed == state) {

            imgbtn.setImageResource(R.drawable.ic_info);
            imgbtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    notifyResult();
                }
            });

        } else
            eAssert(false);
    }

    private void
    setWebSettings(WebView wv) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setSavePassword(true);

        // Enabling cache.
        ws.setDomStorageEnabled(true);
        // Set cache size to 8 mb by default. should be more than enough
        ws.setAppCacheMaxSize(1024*1024*8);
        ws.setAppCachePath(getCacheDir().getAbsolutePath());
        ws.setAllowFileAccess(true);
        ws.setAppCacheEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
    }

    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lv) {
        return "[ ItemViewActivity ]";
    }

    @Override
    public void
    onCreate(Bundle savedInstanceState) {
        UnexpectedExceptionHandler.S().registerModule(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.item_view);
        pb = (ProgressBar)findViewById(R.id.progressbar);
        pb.setMax(100); // to use percent
        wv = (WebView)findViewById(R.id.webview);
        wv.setWebViewClient(new WVClient());
        wv.setWebChromeClient(new WCClient());
        setWebSettings(wv);

        id = getIntent().getLongExtra("id", -1);
        eAssert(id >= 0);

        File f = UIPolicy.getItemDataFile(id);
        netUrl = DBPolicy.S().getItemInfoString(id, DB.ColumnItem.LINK);
        fileUrl = "file:///" + f.getAbsolutePath();
        currUrl = f.exists()? fileUrl: netUrl;
        wv.loadUrl(currUrl);
    }

    @Override
    protected void
    onResume() {
        super.onResume();
        // See comments in 'ChannelListActivity.onResume' around 'registerManagerEventListener'
        RTTask.S().registerManagerEventListener(this, new RTTaskManagerEventHandler());

        // Bind download task if needed
        RTTask.TaskState state = RTTask.S().getState(id, RTTask.Action.Download);
        if (RTTask.TaskState.Idle != state)
            RTTask.S().bind(id, RTTask.Action.Download, this, new DownloadBGTaskOnEvent());

        setupLayout();
    }

    @Override
    protected void
    onPause() {
        logI("==> ItemListActivity : onPause");
        // See comments in 'ChannelListActivity.onPause' around 'unregisterManagerEventListener'
        RTTask.S().unregisterManagerEventListener(this);
        // See comments in 'ChannelListActivity.onPause()'
        RTTask.S().unbind(this);
        super.onPause();
    }

    @Override
    protected void
    onStop() {
        logI("==> ItemListActivity : onStop");
        super.onStop();
    }

    @Override
    protected void
    onDestroy() {
        if (null != wv)
            wv.destroy();
        super.onDestroy();
        UnexpectedExceptionHandler.S().unregisterModule(this);
    }
}
