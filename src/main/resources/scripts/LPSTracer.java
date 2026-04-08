import android.util.Log;

public class LPSTracer {
    private static final String TAG = "LPS_TRACE";

    public static void trace(String fileName, int line, String info) {
        // Формат (File.kt:123) позволяет Android Studio делать ссылку кликабельной
        Log.d(TAG, "[" + info + "] (" + fileName + ":" + line + ")");
    }
}
