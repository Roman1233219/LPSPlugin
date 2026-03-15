import java.util.List;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileOutputStream;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.util.DisplayMetrics;

public class LabelResolver {
    public static void main(String[] args) {
        try {
            // 0. Подготавливаем Looper
            try {
                Class<?> looperClass = Class.forName("android.os.Looper");
                looperClass.getMethod("prepareMainLooper").invoke(null);
            } catch (Exception e) {}

            // 1. Получаем систему
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
            Context systemContext = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
            PackageManager pm = systemContext.getPackageManager();

            File iconDir = new File("/data/local/tmp/icons");
            if (!iconDir.exists()) iconDir.mkdirs();

            List<PackageInfo> packages = pm.getInstalledPackages(0);

            for (PackageInfo pkg : packages) {
                String pkgName = pkg.packageName;
                ApplicationInfo appInfo = pkg.applicationInfo;
                
                if (appInfo != null && appInfo.icon != 0) {
                    try {
                        String labelStr = appInfo.loadLabel(pm).toString();
                        
                        // КЛЮЧЕВОЕ РЕШЕНИЕ: Достаем ресурсы напрямую через путь к APK
                        // Это обходит ограничения контекста app_process
                        Resources res = pm.getResourcesForApplication(appInfo);
                        
                        // Запрашиваем иконку с высокой плотностью (XXHDPI = 480)
                        // чтобы она не была размытой
                        Drawable icon = res.getDrawableForDensity(appInfo.icon, 480, null);

                        if (icon != null) {
                            saveIcon(icon, pkgName);
                            System.out.println(pkgName + "|" + labelStr + "|OK");
                        }
                    } catch (Exception e) {
                        // Если не вышло через ресурсы, пробуем обычный метод (как запасной)
                        try {
                            Drawable icon = appInfo.loadIcon(pm);
                            saveIcon(icon, pkgName);
                            System.out.println(pkgName + "|" + pkgName + "|FALLBACK");
                        } catch (Exception e2) {}
                    }
                }
            }
            System.out.flush();
            System.exit(0);
        } catch (Exception e) {
            System.err.println("CRITICAL: " + e.toString());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void saveIcon(Drawable drawable, String pkgName) {
        try {
            int size = 160;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);

            File file = new File("/data/local/tmp/icons", pkgName + ".png");
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
        } catch (Exception e) {}
    }
}
