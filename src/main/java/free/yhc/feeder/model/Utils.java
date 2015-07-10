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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.apache.http.impl.cookie.DateParseException;
import org.apache.http.impl.cookie.DateUtils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.Layout;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.TextView;
import free.yhc.feeder.R;

public class Utils {
    private static final boolean DBG = false;
    private static final Logger P = new Logger(Utils.class);

    // ========================================================================
    // FOR LOGGING
    // ========================================================================

    // ========================================================================
    //
    // ========================================================================
    // ext2, ext3, ext4 allows 255 bytes for filename.
    // But, in case of UTF-8 (file name encoding at Linux), one character may
    // occupy 1-4 bytes.
    // So, maximum character length for filename is set as 255/4 = 63
    public static final int    MAX_FILENAME_LENGTH = 63;

    public static final long   MIN_IN_MS    = 60 * 1000;
    public static final long   HOUR_IN_MS   = 60 * 60 * 1000;
    public static final long   DAY_IN_MS    = 24 * HOUR_IN_MS;
    public static final int    HOUR_IN_SEC  = 60 * 60;
    public static final int    DAY_IN_SEC   = 24 * HOUR_IN_SEC;

    private static SharedPreferences sPrefs = null;

    // To enable logging to file - NOT LOGCAT
    // These are for debugging purpose
    private static final boolean ENABLE_LOGF = false;
    private static final String  LOGF        = Environment.getExternalStorageDirectory().getAbsolutePath()
                                                  + "/feedhive.log";
    private static final String  LOGF_LAST   = LOGF + "-last";
    private static FileWriter    sLogout     = null;

    // Format of nString (Number String)
    // <number>/<number>/ ... '/' is delimiter between number.
    private static final String NSTRING_DELIMITER = "/";
    // Characters that is not allowed as filename in Android.
    private static final char[] sNoFileNameChars = new char[] {
        '/', '?', '"', '\'', '`', ':', ';', '*', '|', '\\', '<', '>'
    };

    // Belows are not used.
    // Instead of self-implementation, predefined-android-classes are used.
    // index of each value.
    private static final int EXT2MIME_OFFSET_EXT = 0;
    private static final int EXT2MIME_OFFSET_MIME = 1;
    private static final int EXT2MIME_OFFSET_SZ = 2;
    private static String[] mExt2mimeMap = {
            // Image
            "gif",           "image/gif",
            "jpeg",          "image/jpeg",
            "jpg",           "image/jpeg",
            "png",           "image/png",
            "tiff",          "image/tiff",
            "bmp",           "image/bmp", // is this standard???

            // Audio
            "mp3",           "audio/mpeg",
            "aac",           "audio/*",

            // Video
            "mpeg",          "video/mpeg",
            "mp4",           "video/mp4",
            "ogg",           "video/ogg",

            // Text
            "txt",           "text/plain",
            "html",          "text/html",
            "xml",           "text/xml",
    };

    private static String[] mMimeTypes = {
            "application",
            "audio",
            "image",
            "message",
            "model",
            "multipart",
            "text",
            "video",
    };

    /**
     * NOTE
     *  Too many format may drop parsing performance very much.
     *  So, we need to tune this array.
     *  (How many format will be supported?)
     */
    private static final String[] sDateFormats = new String[] {
            org.apache.http.impl.cookie.DateUtils.PATTERN_RFC1036,
            org.apache.http.impl.cookie.DateUtils.PATTERN_RFC1123,
            // Variation of RFC1036
            "EEEE, dd-MMM-yy HH:mm zzz",
            // Variation of RFC1123
            "EEE, dd MMM yyyy HH:mm zzz",
            // To support W3CDTF
            "yyyy-MM-d'T'HH:mm:ssZ",
            "yyyy-MM-d'T'HH:mm:ss'Z'",
            "yyyy-MM-d'T'HH:mm:ss.SSSZ",
            "yyyy-MM-d'T'HH:mm:ss.SSS'Z'",
            // To support some non-standard formats.
            // (I hate this! But lot's of sites don't obey standard!!)
            "yyyy-MM-d HH:mm:ss",
            "yyyy.MM.d HH:mm:ss",
        };

    private static enum LogLV{
        V ("[V]", 6),
        D ("[D]", 5),
        I ("[I]", 4),
        W ("[W]", 3),
        E ("[E]", 2),
        F ("[F]", 1);

        private String pref; // prefix string
        private int    pri;  // priority
        LogLV(String pref, int pri) {
            this.pref = pref;
            this.pri = pri;
        }

        String pref() {
            return pref;
        }

        int pri() {
            return pri;
        }
    }

    public static class Logger {
        private final Class<?> _mCls;

        public Logger(Class<?> cls) {
            _mCls = cls;
        }

        // For logging
        public void v(String msg) { log(_mCls, LogLV.V, msg); }
        public void d(String msg) { log(_mCls, LogLV.D, msg); }
        public void i(String msg) { log(_mCls, LogLV.I, msg); }
        public void w(String msg) { log(_mCls, LogLV.W, msg); }
        public void e(String msg) { log(_mCls, LogLV.E, msg); }
        public void f(String msg) { log(_mCls, LogLV.F, msg); }
    }


    public static enum PrefLayout {
        // Name of echo elements should match values used in the preference.
        RIGHT,
        LEFT,
    }

    public static enum PrefLevel {
        HIGH,
        MEDIUM,
        LOW
    }

    // =======================
    // Private
    // =======================
    static {
        if (ENABLE_LOGF) {
            try {
                File logf = new File(LOGF);
                File logfLast = new File(LOGF_LAST);
                logfLast.delete();
                logf.renameTo(logfLast);
                sLogout = new FileWriter(logf);
            } catch (IOException e) {
                eAssert(false);
            }
        }
    }

    private static void
    log(Class<?> cls, LogLV lv, String msg) {
        if (null == msg)
            return;

        StackTraceElement ste = Thread.currentThread().getStackTrace()[5];
        msg = ste.getClassName() + "/" + ste.getMethodName() + "(" + ste.getLineNumber() + ") : " + msg;

        if (ENABLE_LOGF) {
            try {
                sLogout.write(lv.pref + " " + msg + "\n");
                sLogout.flush();
            } catch (IOException e) {}
        } else {
            switch(lv) {
            case V: Log.v(cls.getSimpleName(), msg); break;
            case D: Log.d(cls.getSimpleName(), msg); break;
            case I: Log.i(cls.getSimpleName(), msg); break;
            case W: Log.w(cls.getSimpleName(), msg); break;
            case E: Log.e(cls.getSimpleName(), msg); break;
            case F: Log.wtf(cls.getSimpleName(), msg); break;
            }
        }
    }

    /**
     * Decode image from file path(String) or raw data (byte[]).
     * @param image
     *   Two types are supported.
     *   String for file path / byte[] for raw image data.
     * @param opt
     * @return
     */
    private static Bitmap
    decodeImageRaw(Object image, BitmapFactory.Options opt) {
        if (image instanceof String) {
            return BitmapFactory.decodeFile((String) image, opt);
        } else if (image instanceof byte[]) {
            byte[] data = (byte[]) image;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opt);
        }
        eAssert(false);
        return null;
    }
    // =======================
    //
    // =======================
    public static void
    init() {
        eAssert(null == sPrefs);
        sPrefs = PreferenceManager.getDefaultSharedPreferences(Environ.getAppContext());
    }

    // Assert
    public static void
    eAssert(boolean cond) {
        if (!cond)
            throw new AssertionError();
    }

    public static String
    getResString(int id) {
        return Environ.getAppContext().getResources().getString(id);
    }

    // ------------------------------------------------------
    // To handle generic array
    // ------------------------------------------------------
    public static <T> T[]
    toArray(List<T> list, T[] a) {
        if (a.length < list.size())
            a = (T[])java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), list.size());
        return list.toArray(a);
    }

    public static <T> T[]
    toArray(List<T> list, Class<T> k) {
        return list.toArray((T[])java.lang.reflect.Array.newInstance(k, list.size()));
    }

    public static <T> T[]
    newArray(Class<T> k, int size) {
        return (T[])java.lang.reflect.Array.newInstance(k, size);
    }

    // ------------------------------------------------------
    //
    // ------------------------------------------------------
    public static boolean
    isUiThread() {
        return Thread.currentThread() == Environ.getUiHandler().getLooper().getThread();
    }

    // Bit mask handling
    public static long
    bitClear(long flag, long mask) {
        return flag & ~mask;
    }

    public static long
    bitSet(long flag, long value, long mask) {
        flag = bitClear(flag, mask);
        return flag | (value & mask);
    }

    public static boolean
    bitIsSet(long flag, long value, long mask) {
        return value == (flag & mask);
    }

    public static long[]
    convertArrayLongTolong(Long[] L) {
        long[] l = new long[L.length];
        for (int i = 0; i < L.length; i++)
            l[i] = L[i];
        return l;
    }

    public static Long[]
    convertArraylongToLong(long[] l) {
        Long[] L = new Long[l.length];
        for (int i = 0; i < l.length; i++)
            L[i] = l[i];
        return L;
    }

    public static int[]
    convertArrayIntegerToint(Integer[] I) {
        int[] i = new int[I.length];
        for (int j = 0; j < I.length; j++)
            i[j] = I[j];
        return i;
    }

    public static Integer[]
    convertArrayintToInteger(int[] i) {
        Integer[] I = new Integer[i.length];
        for (int j = 0; j < i.length; j++)
            I[j] = i[j];
        return I;
    }

    /**
     * Is is valid string?
     * Valid means "Not NULL and Not empty".
     * @param v
     * @return
     */
    public static boolean
    isValidValue(String v) {
        return !(null == v || v.isEmpty());
    }

    public static int
    dpToPx(int dp) {
        return (int) (dp * Environ.getAppContext().getResources().getDisplayMetrics().density);
    }

    public static long
    hourToMs(long hour) {
        return hour * 60 * 60 * 1000;
    }

    public static long
    secToMs(long sec) {
        return sec * 1000;
    }

    /**
     * Convert number string to number array.
     * This function is pair with 'nrsToNString'.
     * @param timeString
     * @return
     */
    public static long[]
    nStringToNrs(String timeString) {
        if (!isValidValue(timeString))
            return new long[0];

        String[] timestrs = timeString.split(NSTRING_DELIMITER);
        long[] times = new long[timestrs.length];
        try {
            for (int i = 0; i < times.length; i++)
                times[i] = Long.parseLong(timestrs[i]);
        } catch (NumberFormatException e) {
            if (DBG) P.w("Invalid time string! [" + timeString + "]");
            eAssert(false);
        }
        return times;
    }

    /**
     * Convert number array to single number string.
     * This is good way to store number array as single string.
     * This function is pair with 'nStringToNrs'.
     * @param nrs
     * @return
     */
    public static String
    nrsToNString(long[] nrs) {
        String nrstr = "";
        if (nrs.length < 1)
            return "";

        for (int i = 0; i < nrs.length - 1; i ++)
            nrstr += nrs[i] + NSTRING_DELIMITER;
        nrstr += nrs[nrs.length - 1];
        return nrstr;
    }

    public static String
    nrsToNString(int[] nrs) {
        String nrstr = "";
        if (nrs.length < 1)
            return "";

        for (int i = 0; i < nrs.length - 1; i ++)
            nrstr += nrs[i] + NSTRING_DELIMITER;
        nrstr += nrs[nrs.length - 1];
        return nrstr;
    }

    /**
     * Convert data string to times in milliseconds since 1970 xxx.
     * @param dateString
     * @return
     *   times in milliseconds. -1 if failed to parse.
     */
    public static long
    dateStringToTime(String dateString) {
        dateString = removeLeadingTrailingWhiteSpace(dateString);
        Date date = null;
        try {
            // instead of using android's DateUtils, apache's DateUtils is used because it is faster.
            date = DateUtils.parseDate(dateString, sDateFormats);
        } catch (DateParseException e) { }
        return (null == date)? -1: date.getTime();
    }

    /**
     * Get size(width, height) of given image.
     * @param image
     *   'image file path' or 'byte[]' image data
     * @param out
     *   out[0] : width of image / out[1] : height of image
     * @return
     *   false if image cannot be decode. true if success
     */
    public static boolean
    imageSize(Object image, int[] out) {
        eAssert(null != image);
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inJustDecodeBounds = true;
        decodeImageRaw(image, opt);
        if (opt.outWidth <= 0 || opt.outHeight <= 0 || null == opt.outMimeType) {
            return false;
        }
        out[0] = opt.outWidth;
        out[1] = opt.outHeight;
        return true;
    }

    /**
     * Calculate rectangle(out[]). This is got by shrinking rectangle(width,height) to
     *   bound rectangle(boundW, boundH) with fixed ratio.
     * If input rectangle is included in bound, then input rectangle itself will be
     *   returned. (we don't need to adjust)
     * @param boundW
     *   width of bound rect
     * @param boundH
     *   height of bound rect
     * @param width
     *   width of rect to be shrunk
     * @param height
     *   height of rect to be shrunk
     * @param out
     *   calculated value [ out[0](width) out[1](height) ]
     * @return
     *   false(not shrunk) / true(shrunk)
     */
    public static boolean
    shrinkFixedRatio(int boundW, int boundH, int width, int height, int[] out) {
        boolean ret;
        // Check size of picture..
        float rw = (float) boundW / (float) width, // width ratio
        rh = (float) boundH / (float) height; // height ratio

        // check whether shrinking is needed or not.
        if (rw >= 1.0f && rh >= 1.0f) {
            // we don't need to shrink
            out[0] = width;
            out[1] = height;
            ret = false;
        } else {
            // shrinking is essential.
            float ratio = (rw > rh) ? rh : rw; // choose minimum
            // integer-type-casting(rounding down) guarantees that value cannot
            // be greater than bound!!
            out[0] = (int) (ratio * width);
            out[1] = (int) (ratio * height);
            ret = true;
        }
        return ret;
    }

    /**
     * Make fixed-ration-bounded-bitmap with file.
     * If (0 >= boundW || 0 >= boundH), original-size-bitmap is trying to be created.
     * @param fpath
     *   image file path (absolute path)
     * @param boundW
     *   bound width
     * @param boundH
     *   bound height
     * @return
     *   null if fails
     */
    public static Bitmap
    decodeImage(Object image, int boundW, int boundH) {
        eAssert(null != image);

        BitmapFactory.Options opt = null;
        if (0 < boundW && 0 < boundH) {
            int[] imgsz = new int[2]; // image size : [0]=width / [1] = height
            if (false == imageSize(image, imgsz)) {
                // This is not proper image data
                return null;
            }

            int[] bsz = new int[2]; // adjusted bitmap size
            boolean bShrink = shrinkFixedRatio(boundW, boundH, imgsz[0], imgsz[1], bsz);

            opt = new BitmapFactory.Options();
            opt.inDither = false;
            if (bShrink) {
                // To save memory we need to control sampling rate. (based on
                // width!)
                // for performance reason, we use power of 2.
                if (0 >= bsz[0])
                    return null;

                int sampleSize = 1;
                while (1 < imgsz[0] / (bsz[0] * sampleSize))
                    sampleSize *= 2;

                // shrinking based on width ratio!!
                // NOTE : width-based-shrinking may make 1-pixel error in height
                // side!
                // (This is not Math!! And we are using integer!!! we cannot
                // make it exactly!!!)
                opt.inScaled = true;
                opt.inSampleSize = sampleSize;
                opt.inDensity = imgsz[0] / sampleSize;
                opt.inTargetDensity = bsz[0];
            }
        }
        return decodeImageRaw(image, opt);
    }

    /**
     * Compress give bitmap to JPEG formatted image data.
     * @param bm
     * @return
     */
    public static byte[]
    compressBitmap(Bitmap bm) {
        long time = System.currentTimeMillis();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        bm.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        if (DBG) P.v("TIME: Compress Image : " + (System.currentTimeMillis() - time));
        return baos.toByteArray();
    }

    /**
     * Text in given TextView is ellipsed?
     * @param tv
     * @return
     */
    public static boolean
    isEllipsed(TextView tv) {
        Layout l = tv.getLayout();
        if (null != l){
            int lines = l.getLineCount();
            if (lines > 0)
                if (l.getEllipsisCount(lines - 1) > 0)
                    return true;
        }
        return false;
    }

    /**
     * @param file
     * @param data
     * @return
     */
    public static Err
    writeToFile(File file, byte[] data) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(data);
        } catch(FileNotFoundException e) {
            return Err.IO_FILE;
        } catch(IOException e) {
            return Err.IO_FILE;
        } finally {
            try {
                if (null != fos)
                    fos.close();
            } catch (IOException e) {}
        }
        return Err.NO_ERR;
    }

    /**
     *
     * @param file
     *   Text file.
     * @return
     *   value when reading non-text files, is not defined.
     */
    public static String
    readTextFile(File file) {
        try {
            StringBuffer fileData = new StringBuffer(4096);
            BufferedReader reader = new BufferedReader(new FileReader(file));
            char[] buf = new char[4096];
            int bytes;
            while(-1 != (bytes = reader.read(buf)))
                fileData.append(buf, 0, bytes);
            reader.close();
            return fileData.toString();
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Copy from input stream to output stream.
     * @param os
     * @param is
     */
    public static void
    copy(OutputStream os, InputStream is) throws IOException {
        byte buf[]=new byte[1024 * 16];
        int len;
        while((len = is.read(buf)) > 0)
            os.write(buf, 0, len);
    }

    public static String
    getExtentionFromUrl(String url) {
        /*
         * Sometimes MimeTypeMap.getFileExtentionFromUrl returns null event if url is valid
         * (Especially, in case that URL contains Korean).
         * So, DO NOT USE it!
        String ext = MimeTypeMap.getFileExtensionFromUrl(url);
        if (isValidValue(ext))
            return ext;
        */
        URL u;
        try {
            u = new URL(url);
        } catch (MalformedURLException e) {
            return "";
        }
        String path = u.getPath();
        int i = path.lastIndexOf('.');
        if (i < 0)
            return "";
        try {
            return path.substring(i + 1);
        } catch (IndexOutOfBoundsException e) {
            return "";
        }
    }

    public static String
    guessMimeTypeFromUrl(String url) {
        String ext = getExtentionFromUrl(url);
        // NOTE
        // "MimeTypeMap.getSingleton().getMimeTypeFromExtension()" doesn't work for
        //   uppercase-extension - ex. MP3.
        // For workaround, converted lowercase is used.
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
    }

    public static boolean
    isAudioOrVideo(String url) {
        String mime = guessMimeTypeFromUrl(url);
        return null != mime && (mime.startsWith("audio/") || mime.startsWith("video/"));
    }

    /**
     * Does given string represents Mime Type?
     * @param str
     * @return
     */
    public static boolean
    isMimeType(String str) {
        // Let's reuse Android class
        return MimeTypeMap.getSingleton().hasMimeType(str);
    }

    /**
     * Convert given string to valid (OS supported) file name.
     * To do this, some characters that are not allowed as file name, are replaced with
     *   other characters.
     * @param str
     * @return
     */
    public static String
    convertToFilename(String str) {
        // Most Unix (including Linux) allows all 8bit-character as file name
        //   except for ('/' and 'null').
        // But android shell doens't allows some characters.
        // So, we need to handle those...
        for (char c : sNoFileNameChars)
            str = str.replace(c, '~');
        return str;
    }

    /**
     * Remove file and directory recursively
     * @param f
     *   file or directory.
     * @param bDeleteMe
     *   'true' means delete given directory itself too.
     * @return
     *   false:  fail to full-delete
     */
    public static boolean
    removeFileRecursive(File f, boolean bDeleteMe) {
        boolean ret = true;
        if (f.isDirectory()) {
            for (File c : f.listFiles())
                if (!removeFileRecursive(c, true))
                    ret = false;
        }
        if (ret && bDeleteMe)
            return f.delete();
        return ret;
    }

    public static void
    getFilesRecursive(LinkedList<File> l, File f) {
        if (!f.exists())
            return;

        if (f.isDirectory()) {
            for (File c : f.listFiles())
                    getFilesRecursive(l, c);
        } else
            l.add(f);
    }

    public static String
    removeLeadingTrailingWhiteSpace(String s) {
        s = s.replaceFirst("^\\s+", "");
        return s.replaceFirst("\\s+$", "");
    }

    public static String
    removeTrailingSlash(String url) {
        // Remove trailing '/'
        // "http://xxx/" is same with "http://xxx"
        if (url.endsWith("/"))
            url = url.substring(0, url.lastIndexOf('/'));
        return url;
    }

    /**
     * Is any available active network at this device?
     * @return
     */
    public static boolean
    isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager)Environ
                .getAppContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni;

        if (isPrefUseWifiOnly())
            ni = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        else
            ni = cm.getActiveNetworkInfo();

        if (null != ni)
            return ni.isConnectedOrConnecting();
        else
            return false;
    }

    public static File
    getNewTempFile() {
        File ret = null;
        try {
            ret = File.createTempFile("free.yhc.feeder", null, Environ.get().getTempDirFile());
        } catch (IOException ignored){ }
        return ret;
    }

    public static void
    cleanTempFiles() {
        removeFileRecursive(Environ.get().getTempDirFile(), false);
    }

    // ------------------------------------------------------------------------
    //
    // Accessing preference
    //
    // ------------------------------------------------------------------------
    // Preference for internal use.
    private static boolean
    isPrefUseWifiOnly() {
        return sPrefs.getBoolean(getResString(R.string.csuse_wifi_only), false);
    }

    public static boolean
    isPrefNewmsgNoti() {
        return sPrefs.getBoolean(getResString(R.string.csnewmsg_noti), true);
    }

    public static int
    getPrefMaxNrBgTask() {
        String v = sPrefs.getString(getResString(R.string.csmaxnr_bgtask), "2");
        int value = 2;
        try {
            value = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            eAssert(false);
        }
        return value;
    }

    /**
     * Get BG task thread priority from shared preference.
     * @param context
     * @return
     *   Value of Java Thread priority (between Thread.MIN_PRIORITY and Thread.MAX_PRIORITY)
     */
    public static int
    getPrefBGTaskPriority() {
        String prio = sPrefs.getString(getResString(R.string.csbgtask_prio),
                                       getResString(R.string.cslow));
        if (getResString(R.string.cslow).equals(prio))
            return Thread.MIN_PRIORITY;
        else if (getResString(R.string.csmedium).equals(prio))
            return (Thread.NORM_PRIORITY + Thread.MIN_PRIORITY) / 2;
        else if (getResString(R.string.cshigh).equals(prio))
            return Thread.NORM_PRIORITY;
        else {
            eAssert(false);
            return Thread.MIN_PRIORITY;
        }
    }

    public static PrefLevel
    getPrefMemConsumptionLevel() {
        // See preference.xml for meaning of each number value.
        String lv = sPrefs.getString(getResString(R.string.csmem_consumption),
                getResString(R.string.csmedium));
        if (getResString(R.string.cslow).equals(lv))
            return PrefLevel.LOW;
        else if (getResString(R.string.csmedium).equals(lv))
            return PrefLevel.MEDIUM;
        else if (getResString(R.string.cshigh).equals(lv))
            return PrefLevel.HIGH;
        else {
            eAssert(false);
            return PrefLevel.MEDIUM;
        }
    }

    public static int
    getPrefContentVersion() {
        return sPrefs.getInt(getResString(R.string.cscontent_version), 0);
    }

    public static PrefLayout
    getPrefAppWidgetButtonLayout() {
        return PrefLayout.valueOf(sPrefs.getString(getResString(R.string.csappwidget_btn_layout),
                                                   PrefLayout.RIGHT.name()));
    }
    // ================================================
    //
    // Utility Functions for Date
    //
    // ================================================

    /**
     * Get time milliseconds at 00:00:00(hh:mm:ss) of given day.
     * For example, calling function with argument "2012-12-25, 13:46:57" calendar value,
     *   gives time milliseconds of "2012-12-25, 00:00:00".
     * @param cal
     * @return
     */
    public static long
    dayBaseMs(Calendar cal) {
        Calendar temp = Calendar.getInstance();
        temp.setTime(cal.getTime());
        // Set to 00:00:00:000
        temp.set(Calendar.HOUR_OF_DAY, 0);
        temp.set(Calendar.MINUTE, 0);
        temp.set(Calendar.SECOND, 0);
        temp.set(Calendar.MILLISECOND, 0);
        return temp.getTimeInMillis();
    }

    // SIDE EFFECT!
    //   'secs' is sorted by ascending numerical order!!
    //
    // @secs
    //      00:00:00 based value.
    //      seconds since 00:00:00 (12:00 AM)
    //      (negative value is NOT ALLOWED)
    //      Order may be changed (sorted) to ascending order.
    // @return : time to next nearest time (ms based on 1970....)
    /**
     * NOTE
     * 'secs' array is [in/out] argument.
     * @param calNow
     * @param secs
     *   [in/out] After return, array is sorted by ascending numerical order.
     *   00:00:00 based value.
     *   seconds since 00:00:00 (12:00 AM)
     *   (negative value is NOT ALLOWED)
     *   Order may be changed (sorted) to ascending order.
     * @return
     *   time(ms based on 1970) to next nearest time of given second-of-day array.
     */
    public static long
    nextNearestTime(Calendar calNow, long[] secs) {
        eAssert(secs.length > 0);
        Calendar cal = Calendar.getInstance();
        cal.setTime(calNow.getTime());

        long now = cal.getTimeInMillis();
        cal.set(Calendar.HOUR, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long dayBase = cal.getTimeInMillis();
        long dayTime = now - dayBase;
        if (dayTime < 0)
            dayTime = 0; // To compensate error from '/' operation.

        Arrays.sort(secs);
        // Just linear search... it's enough.
        // Binary search is not considered yet.(over-engineering)
        for (long s : secs) {
            eAssert(s >= 0);
            if (s * 1000 > dayTime)
                return dayTime + s * 1000;
        }
        // All scheduled time is passed for day.
        // smallest of tomorrow is nearest one.
        return dayBase + DAY_IN_MS + secs[0] * 1000;
    }

    /**
     * Covert month(Gregorian calendar) to Android Calendar's month-value.
     * For example, 1 -> Calendar.JANUARY
     * @param mon
     * @return
     */
    public static int
    monthToCalendarMonth(int mon) {
        // Android Calendar starts month from 0
        // That is JANUARY is 0
        return mon - 1;
    }

    /**
     * Android Calendar's month-value to month(Gregorian calendar).
     * For example, Calendar.JANUARY -> 1
     * @param calMon
     * @return
     */
    public static int
    calendarMonthToMonth(int calMon) {
        // Android Calendar starts month from 0
        // That is JANUARY is 0
        return calMon + 1;
    }
    /**
     *
     * @param since
     * @param now
     * @param year
     * @return
     *   null if error (ex. "since > now", "year < since" or "year > now")
     *   otherwise int[2] is returned.
     *   int[0] : min month (inclusive) [1 ~ 12]
     *   int[1] : max month (inclusive) [1 ~ 12]
     */
    public static int[]
    getMonths(Calendar since, Calendar now, int year) {
        int sy = since.get(Calendar.YEAR);
        int ny = now.get(Calendar.YEAR);
        if (since.getTimeInMillis() > now.getTimeInMillis()
            || sy > year
            || ny < year)
            return null;

        // check trivial case at first
        if (year > sy && year < ny)
            return new int[] {1, 12};

        int minm = 1;  // min month
        int maxm = 12; // max month
        if (year == sy)
            minm = calendarMonthToMonth(since.get(Calendar.MONTH));
        if (year == ny)
            maxm = calendarMonthToMonth(now.get(Calendar.MONTH));
        return new int[] {minm, maxm};
    }



    // ================================================
    //
    // Utility Functions for Youtube
    //
    // ================================================

    // NOTE
    // At youtube API document, query "?q=xxxxxx&author=yyyy" should returns
    //   query and author both matches.
    // But, in reality, it doesn't work as expected.
    // I think it's youtube's bug.
    // So, until youtube query works as expected, combined query is not useful.
    // So, just search by 'uploader' and 'keyword' are allowed.
    public static String
    buildYoutubeFeedUrl_uploader(String uploader) {
        return "http://gdata.youtube.com/feeds/api/users/"
                + Uri.encode(uploader, null)
                + "/uploads?format=5";
    }

    public static String
    buildYoutubeFeedUrl_search(String search) {
        search = search.replaceAll("\\s+", "+");
        return "http://gdata.youtube.com/feeds/api/videos?q="
                + Uri.encode(search, "+")
                + "&start-index=1&max-results=50&client=ytapi-youtube-search&orderby=published&format=5&v=2";
    }
}
