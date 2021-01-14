package com.android.ks.tool.installer;

import android.annotation.SuppressLint;
import android.app.Application;
import android.util.Log;

import com.android.ks.tool.installer.util.InstallZipUtil;

import org.ks.tool.manager.MeowCatApplication;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

@SuppressLint("Registered")
public class XposedApp extends Application {
    private static final File EDXPOSED_PROP_FILE = new File("/system/framework/edconfig.jar");
    private static XposedApp mInstance = null;
    public InstallZipUtil.XposedProp mXposedProp;

    public static XposedApp getInstance() {
        return mInstance;
    }

    public static Integer getActiveXposedVersion() {
        Log.d(MeowCatApplication.TAG, "EdXposed is not active");
        return -1;
    }

    public void onCreate() {
        super.onCreate();
        mInstance = this;
        reloadXposedProp();
    }

    public void reloadXposedProp() {
        InstallZipUtil.XposedProp prop = null;
        File file = null;

        if (EDXPOSED_PROP_FILE.canRead()) {
            file = EDXPOSED_PROP_FILE;
        }

        if (file != null) {
            try (FileInputStream is = new FileInputStream(file)) {
                prop = InstallZipUtil.parseXposedProp(is);
            } catch (IOException e) {
                Log.e(MeowCatApplication.TAG, "Could not read " + file.getPath(), e);
            }
        }
        synchronized (this) {
            mXposedProp = prop;
        }
    }
}
