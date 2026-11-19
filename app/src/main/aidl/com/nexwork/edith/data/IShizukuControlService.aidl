package com.nexwork.edith.data;

interface IShizukuControlService {
    void destroy() = 16777114;
    boolean grantLauncherPermissions(String packageName, String listenerComponent) = 1;
    boolean setWifiEnabled(boolean enabled) = 2;
    boolean setBluetoothEnabled(boolean enabled) = 3;
    boolean setDndEnabled(boolean enabled) = 4;
    boolean setRefreshRateHz(int hz) = 5;
    String readSystemStatus() = 6;
}
