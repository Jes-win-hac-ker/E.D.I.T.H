package com.nexwork.edith.data;

import android.os.IBinder;

interface IShizukuMonetService {
    void destroy() = 16777114;
    IBinder getOverlayManagerBinder() = 1;
    boolean enableAndPrioritizeOverlay(String packageName, String overlayName, int userId) = 2;
    boolean disableOverlay(String packageName, String overlayName, int userId) = 3;
    String readThemeCustomizationJson() = 4;
    boolean writeThemeCustomizationJson(String json) = 5;
    boolean isOverlayActiveAtTop(String packageName, String overlayName, int userId) = 6;
}
