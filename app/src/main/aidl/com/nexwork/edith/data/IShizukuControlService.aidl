package com.nexwork.edith.data;

interface IShizukuControlService {
    void destroy() = 16777114;
    boolean grantLauncherPermissions(String packageName, String listenerComponent) = 1;
    boolean setWifiEnabled(boolean enabled) = 2;
    boolean setBluetoothEnabled(boolean enabled) = 3;
    boolean setDndEnabled(boolean enabled) = 4;
    boolean setRefreshRateHz(int hz) = 5;
    String readSystemStatus() = 6;
    String dumpUiHierarchyXml() = 7;
    boolean inputTap(int x, int y) = 8;
    boolean inputSwipe(int x1, int y1, int x2, int y2, int durationMs) = 9;
    boolean inputTextShell(String escapedText) = 10;
    boolean inputKeyEvent(int keyCode) = 11;
}
