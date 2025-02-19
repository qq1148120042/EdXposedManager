package org.ks.tool.manager.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.FileUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.snackbar.Snackbar;

import org.ks.annotation.NotProguard;
import org.ks.tool.manager.ModulesFragment;
import org.ks.tool.manager.R;
import org.ks.tool.manager.StatusInstallerFragment;
import org.ks.tool.manager.XposedApp;
import org.ks.tool.manager.repo.ModuleVersion;
import org.ks.tool.manager.repo.RepoDb;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.ks.tool.manager.MeowCatApplication.TAG;
import static org.ks.tool.manager.XposedApp.rw_rw_r__;

public final class ModuleUtil {
    // ksminversion below this
    private static final String MODULES_LIST_FILE = XposedApp.BASE_DIR + "conf/modules.list";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";
    public static int MIN_MODULE_VERSION = 2; // reject modules with
    private static ModuleUtil mInstance = null;
    private final XposedApp mApp;
    private final PackageManager mPm;
    private final String mFrameworkPackageName;
    private final List<ModuleListener> mListeners = new CopyOnWriteArrayList<>();
    private SharedPreferences mPref;
    private Map<String, InstalledModule> mInstalledModules;
    private boolean mIsReloading = false;

    private ModuleUtil() {
        mApp = XposedApp.getInstance();
        mPref = mApp.getSharedPreferences("enabled_modules", Context.MODE_PRIVATE);
        mPm = mApp.getPackageManager();
        mFrameworkPackageName = mApp.getPackageName();
    }

    public static synchronized ModuleUtil getInstance() {
        if (mInstance == null) {
            mInstance = new ModuleUtil();
            mInstance.reloadInstalledModules();
        }
        return mInstance;
    }

    public static int extractIntPart(String str) {
        int result = 0, length = str.length();
        for (int offset = 0; offset < length; offset++) {
            char c = str.charAt(offset);
            if ('0' <= c && c <= '9')
                result = result * 10 + (c - '0');
            else
                break;
        }
        return result;
    }

    public void reloadInstalledModules() {
        synchronized (this) {
            if (mIsReloading)
                return;
            mIsReloading = true;
        }

        Map<String, InstalledModule> modules = new HashMap<>();
        RepoDb.beginTransation();
        try {
            RepoDb.deleteAllInstalledModules();

            for (PackageInfo pkg : mPm.getInstalledPackages(PackageManager.GET_META_DATA)) {
                ApplicationInfo app = pkg.applicationInfo;
                if (!app.enabled)
                    continue;

                InstalledModule installed = null;
                if (app.metaData != null && app.metaData.containsKey("ksmodule")) {
                    installed = new InstalledModule(pkg, false);
                    modules.put(pkg.packageName, installed);
                }

                if (installed != null)
                    RepoDb.insertInstalledModule(installed);
            }

            RepoDb.setTransactionSuccessful();
        } finally {
            RepoDb.endTransation();
        }

        mInstalledModules = modules;
        synchronized (this) {
            mIsReloading = false;
        }
        for (ModuleListener listener : mListeners) {
            listener.onInstalledModulesReloaded(mInstance);
        }
    }

    public InstalledModule reloadSingleModule(String packageName) {
        PackageInfo pkg;
        try {
            pkg = mPm.getPackageInfo(packageName, PackageManager.GET_META_DATA);
        } catch (NameNotFoundException e) {
            RepoDb.deleteInstalledModule(packageName);
            InstalledModule old = mInstalledModules.remove(packageName);
            if (old != null) {
                for (ModuleListener listener : mListeners) {
                    listener.onSingleInstalledModuleReloaded(mInstance, packageName, null);
                }
            }
            return null;
        }

        ApplicationInfo app = pkg.applicationInfo;
        if (app.enabled && app.metaData != null && app.metaData.containsKey("ksmodule")) {
            InstalledModule module = new InstalledModule(pkg, false);
            RepoDb.insertInstalledModule(module);
            mInstalledModules.put(packageName, module);
            for (ModuleListener listener : mListeners) {
                listener.onSingleInstalledModuleReloaded(mInstance, packageName,
                        module);
            }
            return module;
        } else {
            RepoDb.deleteInstalledModule(packageName);
            InstalledModule old = mInstalledModules.remove(packageName);
            if (old != null) {
                for (ModuleListener listener : mListeners) {
                    listener.onSingleInstalledModuleReloaded(mInstance, packageName, null);
                }
            }
            return null;
        }
    }

    public synchronized boolean isLoading() {
        return mIsReloading;
    }

    public String getFrameworkPackageName() {
        return mFrameworkPackageName;
    }

    public InstalledModule getModule(String packageName) {
        return mInstalledModules.get(packageName);
    }

    public Map<String, InstalledModule> getModules() {
        return mInstalledModules;
    }

    public void setModuleEnabled(String packageName, boolean enabled) {
        if (enabled) {
            mPref.edit().putInt(packageName, 1).apply();
        } else {
            mPref.edit().remove(packageName).apply();
        }
    }

    public boolean isModuleEnabled(String packageName) {
        return mPref.contains(packageName);
    }

    public List<InstalledModule> getEnabledModules() {
        LinkedList<InstalledModule> result = new LinkedList<>();

        for (String packageName : mPref.getAll().keySet()) {
            InstalledModule module = getModule(packageName);
            if (module != null)
                result.add(module);
            else
                setModuleEnabled(packageName, false);
        }

        return result;
    }

    @NotProguard
    public synchronized void updateModulesList(boolean showToast, View view) {
        try {
            Log.i(TAG, "ModuleUtil -> updating modules.list");
            int installedXposedVersion = XposedApp.getActiveXposedVersion();
            boolean disabled = StatusInstallerFragment.DISABLE_FILE.exists();
            if (!XposedApp.getPreferences().getBoolean("skip_ksminversion_check", false) && !disabled && installedXposedVersion <= 0 && showToast) {
                Snackbar.make(view, R.string.notinstalled, Snackbar.LENGTH_SHORT).show();
                return;
            }

            PrintWriter modulesList = new PrintWriter(MODULES_LIST_FILE);
            PrintWriter enabledModulesList = new PrintWriter(XposedApp.ENABLED_MODULES_LIST_FILE);
            List<InstalledModule> enabledModules = getEnabledModules();
            for (InstalledModule module : enabledModules) {

                if (!XposedApp.getPreferences().getBoolean("skip_ksminversion_check", false) && (!disabled && (module.minVersion > installedXposedVersion || module.minVersion < MIN_MODULE_VERSION)) && showToast) {
                    Snackbar.make(view, R.string.notinstalled, Snackbar.LENGTH_SHORT).show();
                    continue;
                }

                modulesList.println(module.app.sourceDir);

                try {
                    String installer = mPm.getInstallerPackageName(module.app.packageName);
                    if (!PLAY_STORE_PACKAGE.equals(installer))
                        enabledModulesList.println(module.app.packageName);
                } catch (Exception ignored) {
                }
            }
            modulesList.close();
            enabledModulesList.close();

            FileUtils.setPermissions(MODULES_LIST_FILE, rw_rw_r__, -1, -1);
            FileUtils.setPermissions(XposedApp.ENABLED_MODULES_LIST_FILE, rw_rw_r__, -1, -1);

            if (showToast) {
                Snackbar.make(view, R.string.xposed_module_list_updated, Snackbar.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Log.e(TAG, "ModuleUtil -> cannot write " + MODULES_LIST_FILE, e);
            Toast.makeText(mApp, "cannot write " + MODULES_LIST_FILE + e, Toast.LENGTH_SHORT).show();
        }
    }

    public void addListener(ModuleListener listener) {
        if (!mListeners.contains(listener))
            mListeners.add(listener);
    }

    public void removeListener(ModuleListener listener) {
        mListeners.remove(listener);
    }

    public interface ModuleListener {
        /**
         * Called whenever one (previously or now) installed module has been
         * reloaded
         */
        void onSingleInstalledModuleReloaded(ModuleUtil moduleUtil, String packageName, InstalledModule module);

        /**
         * Called whenever all installed modules have been reloaded
         */
        void onInstalledModulesReloaded(ModuleUtil moduleUtil);
    }

    public class InstalledModule {
        //private static final int FLAG_FORWARD_LOCK = 1 << 29;
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final int minVersion;
        public final long installTime;
        public final long updateTime;
        final boolean isFramework;
        public ApplicationInfo app;
        private String appName; // loaded lazyily
        private String description; // loaded lazyily

        private Drawable.ConstantState iconCache = null;

        @SuppressWarnings("deprecation")
        private InstalledModule(PackageInfo pkg, boolean isFramework) {
            this.app = pkg.applicationInfo;
            this.packageName = pkg.packageName;
            this.isFramework = isFramework;
            this.versionName = pkg.versionName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                this.versionCode = pkg.getLongVersionCode();
            } else {
                this.versionCode = pkg.versionCode;
            }
            this.installTime = pkg.firstInstallTime;
            this.updateTime = pkg.lastUpdateTime;

            if (isFramework) {
                this.minVersion = 0;
                this.description = "";
            } else {
                int version = XposedApp.getActiveXposedVersion();
                if (version > 0 && XposedApp.getPreferences().getBoolean("skip_ksminversion_check", false)) {
                    this.minVersion = version;
                } else {
                    Object minVersionRaw = app.metaData.get("ksminversion");
                    if (minVersionRaw instanceof Integer) {
                        this.minVersion = (int) minVersionRaw;
                    } else if (minVersionRaw instanceof String) {
                        this.minVersion = extractIntPart((String) minVersionRaw);
                    } else {
                        this.minVersion = 0;
                    }
                }
            }
        }

        public boolean isInstalledOnExternalStorage() {
            return (app.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
        }

//        public boolean isForwardLocked() {
//            return (app.flags & FLAG_FORWARD_LOCK) != 0;
//        }

        public String getAppName() {
            if (appName == null)
                appName = app.loadLabel(mPm).toString();
            return appName;
        }

        public String getDescription() {
            if (this.description == null) {
                Object descriptionRaw = app.metaData.get("ksdescription");
                String descriptionTmp = null;
                if (descriptionRaw instanceof String) {
                    descriptionTmp = ((String) descriptionRaw).trim();
                } else if (descriptionRaw instanceof Integer) {
                    try {
                        int resId = (Integer) descriptionRaw;
                        if (resId != 0)
                            descriptionTmp = mPm.getResourcesForApplication(app).getString(resId).trim();
                    } catch (Exception ignored) {
                    }
                }
                this.description = (descriptionTmp != null) ? descriptionTmp : "";
            }
            return this.description;
        }

        public boolean isUpdate(ModuleVersion version) {
            return (version != null) && version.code > versionCode;
        }

        public Drawable getIcon() {
            if (iconCache != null)
                return iconCache.newDrawable();

            Intent mIntent = new Intent(Intent.ACTION_MAIN);
            mIntent.addCategory(ModulesFragment.SETTINGS_CATEGORY);
            mIntent.setPackage(app.packageName);
            List<ResolveInfo> ris = mPm.queryIntentActivities(mIntent, 0);

            Drawable result;
            if (ris == null || ris.size() <= 0)
                result = app.loadIcon(mPm);
            else
                result = ris.get(0).activityInfo.loadIcon(mPm);
            iconCache = result.getConstantState();

            return result;
        }

        @NonNull
        @Override
        public String toString() {
            return getAppName();
        }
    }
}