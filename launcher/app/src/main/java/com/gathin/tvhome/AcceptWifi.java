package com.gathin.tvhome;

/**
 * Run by TV Home's debugging shell (app_process), not inside the app: tells Android to keep a Wi-Fi the
 * user chose even when its own internet check fails, like answering "Stay connected? Yes, always" in
 * Settings. The box's check can fail on a hotspot that works (its IPv6 route never answers), and Android
 * then drops it. Needs the shell's network-settings permission, which the app doesn't have.
 * Usage: CLASSPATH=base.apk app_process / com.gathin.tvhome.AcceptWifi <netId>
 */
public final class AcceptWifi {
    public static void main(String[] args) throws Exception {
        android.os.Parcel p = android.os.Parcel.obtain();
        p.writeInt(Integer.parseInt(args[0]));   // a Network is parcelled as its id
        p.setDataPosition(0);
        android.net.Network n = android.net.Network.CREATOR.createFromParcel(p);
        p.recycle();
        android.os.IBinder b = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "connectivity");
        Object cm = Class.forName("android.net.IConnectivityManager$Stub")
                .getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
        cm.getClass().getMethod("setAcceptUnvalidated", android.net.Network.class, boolean.class, boolean.class)
                .invoke(cm, n, true, true);
        System.out.println("kept " + n);
    }
}
