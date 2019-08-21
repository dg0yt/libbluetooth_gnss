package com.clearevo.libbluetooth_gnss_service;


import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.os.SystemClock;
import android.location.Location;

import com.clearevo.libecodroidbluetooth.rfcomm_conn_callbacks;
import com.clearevo.libecodroidbluetooth.rfcomm_conn_mgr;
import com.clearevo.libecodroidgnss_parse.gnss_sentence_parser;

import java.util.List;


public class bluetooth_gnss_service extends Service implements rfcomm_conn_callbacks {

    static final String TAG = "btgnss_service";

    rfcomm_conn_mgr g_rfcomm_mgr = null;
    public gnss_sentence_parser m_gnss_parser = new gnss_sentence_parser();

    final String EDG_DEVICE_PREFIX = "EcoDroidGPS";
    public static final String BROADCAST_ACTION_NMEA = "com.clearevo.bluetooth_gnss.NMEA";
    Thread m_connecting_thread = null;
    Handler m_handler = new Handler();
    String m_bdaddr = "";
    Class m_target_activity_class;
    int m_icon_id;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If we get killed, after returning from here, restart
        Log.d(TAG, "onStartCommand");

        if (intent != null) {
            try {
                m_bdaddr = intent.getStringExtra("bdaddr");
                String cn = intent.getStringExtra("activity_class_name");
                if (cn == null) {
                    throw new Exception("activity_class_name not specified");
                }
                m_target_activity_class = Class.forName(cn);
                if (!intent.hasExtra("activity_icon_id")){
                    throw new Exception("activity_icon_id not specified");
                }
                m_icon_id = intent.getIntExtra("activity_icon_id", 0);

                if (m_bdaddr == null) {
                    String msg = "bluetooth_gnss_service: startservice: Target Bluetooth device not specifed - cannot start...";
                    Log.d(TAG, msg);
                    toast(msg);
                } else {
                    Log.d(TAG, "onStartCommand got bdaddr");
                    int start_ret = connect(m_bdaddr);
                    if (start_ret == 0) {
                        start_foreground("Connecting...", "target device: " + m_bdaddr, "");
                    }
                }
            } catch (Exception e) {
                String msg = "bluetooth_gnss_service: startservice: parse intent failed - cannot start... - exception: "+Log.getStackTraceString(e);
                Log.d(TAG, msg);
            }
        } else {
            String msg = "bluetooth_gnss_service: startservice: null intent - cannot start...";
            Log.d(TAG, msg);
            toast(msg);
        }

        return START_REDELIVER_INTENT;
    }

    public boolean is_bt_connected()
    {
        if (g_rfcomm_mgr != null && g_rfcomm_mgr.is_bt_connected()) {
            return true;
        }
        return false;
    }


    public boolean is_conn_thread_alive() {
        return m_connecting_thread != null && m_connecting_thread.isAlive();
    }


    int connect(String bdaddr)
    {
        int ret = -1;

        try {

            //new instance to clear state but hook to old callback if exists
            gnss_sentence_parser.gnss_parser_callbacks old_cb = m_gnss_parser.get_callback();
            m_gnss_parser = new gnss_sentence_parser(); //use new instance
            m_gnss_parser.set_callback(old_cb);

            if (is_conn_thread_alive()) {
                toast("connection already ongoing - please wait...");
                return 1;
            } else if (g_rfcomm_mgr != null && g_rfcomm_mgr.is_bt_connected()) {
                toast("already connected - press Back to disconnect and exit...");
                return 2;
            } else {
                toast("connecting to: "+bdaddr);
                if (g_rfcomm_mgr != null) {
                    g_rfcomm_mgr.close();
                }
                BluetoothDevice dev = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(bdaddr);
                if (dev == null) {
                    toast("Please pair your Bluetooth GPS Receiver in phone Bluetooth Settings...");
                    throw new Exception("no paired bluetooth devices...");
                } else {
                    //ok
                }
                Log.d(TAG, "using dev: " + dev.getAddress());
                g_rfcomm_mgr = new rfcomm_conn_mgr(dev, this);

                m_connecting_thread = new Thread() {
                    public void run() {
                        try {
                            g_rfcomm_mgr.connect();
                        } catch (Exception e) {
                            m_handler.post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("Connect failed... Please make sure your device is ready...");
                                            updateNotification("Connect failed...", "Target device: "+m_bdaddr, "");
                                        }
                                    }
                            );
                            Log.d(TAG, "g_rfcomm_mgr connect exception: "+Log.getStackTraceString(e));
                        }
                    }
                };

                m_connecting_thread.start();

            }
            ret = 0;
        } catch (Exception e) {
            String emsg = Log.getStackTraceString(e);
            Log.d(TAG, "connect() exception: "+emsg);
            toast("Connect failed: "+emsg);
        }

        return ret;
    }

    //return true if was connected
    boolean close()
    {
        if (g_rfcomm_mgr != null) {
            boolean was_connected = g_rfcomm_mgr.is_bt_connected();
            g_rfcomm_mgr.close();
            return was_connected;
        }

        return false;
    }

    void toast(String msg)
    {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "toast msg: "+msg);
    }

    public void on_rfcomm_connected()
    {
        Log.d(TAG, "on_rfcomm_connected()");
        m_handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        toast("Connected...");
                        updateNotification("Connected...", "Target device: "+m_bdaddr, "");
                    }
                }
        );
    }

    public void on_rfcomm_disconnected()
    {
        Log.d(TAG, "on_rfcomm_disconnected()");
        m_handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        toast("Disconnected...");
                        updateNotification("Disconnected...", "Target device: "+m_bdaddr, "");
                    }
                }
        );
    }

    public void on_readline(String readline)
    {
        Log.d(TAG, "on_readline()");
        String parsed_nmea = m_gnss_parser.parse(readline);

        if (parsed_nmea != null) {
            Intent intent = new Intent();
            intent.setAction(BROADCAST_ACTION_NMEA);
            intent.putExtra("NMEA", parsed_nmea);
            getApplicationContext().sendBroadcast(intent);
        }
    }

    public void on_readline_stream_connected()
    {
        Log.d(TAG, "on_readline_stream_connected()");
        m_handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        toast("Data stream connected...");
                        updateNotification("Connected...", "Target device: "+m_bdaddr, "");
                    }
                }
        );
    }

    public void on_readline_stream_closed()
    {
        Log.d(TAG, "on_readline_stream_closed()");
        m_handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        toast("Data stream disconnected...");
                        updateNotification("Disconnected...", "Target device: "+m_bdaddr, "");
                    }
                }
        );
    }

    public void on_target_tcp_connected(){}
    public void on_target_tcp_disconnected(){}


    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");
        super.onCreate();
    }

    void start_foreground(String title, String text, String ticker)
    {
        Log.d(TAG, "start_forgroud 0");

        startForeground(1, getMyActivityNotification(title, text, ticker));
        Log.d(TAG, "start_forgroud end");
    }

    String notification_channel_id = "BLUETOOTH_GNSS_CHANNEL_ID";
    String notification_name = "BLUETOOTH_GNSS";

    private Notification getMyActivityNotification(String title, String text, String ticker){

        Intent notificationIntent = new Intent(this, m_target_activity_class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, 0);


        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(notification_channel_id,
                    notification_name,
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Bluetooth GNSS Status");
            mNotificationManager.createNotificationChannel(channel);
        }

        Notification notification =
                new Notification.Builder(this, notification_channel_id)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(m_icon_id)
                        .setContentIntent(pendingIntent)
                        .setTicker(ticker)
                        .build();

        return notification;
    }

    private void updateNotification(String title, String text, String ticker) {
        Notification notification = getMyActivityNotification(title, text, ticker);

        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(1, notification);
    }

    public static boolean is_location_enabled(Context context)
    {
        Log.d(TAG, "is_location_enabled() 0");
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        boolean gps_enabled = false;

        try {
            Log.d(TAG, "is_location_enabled() getting providers");
            List<String> providers = lm.getAllProviders();
            for (String p : providers) {
                Log.d(TAG,"location provider enabled: "+p);
            }

            Log.d(TAG, "is_location_enabled() 1");

            gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch(Exception e) {
            Log.d(TAG, "check gps_enabled exception: "+Log.getStackTraceString(e));
        }
        return gps_enabled;

    }

    public static final String MOCK_LOCATION_PROVIDER_NAME = "com.clearevo.bluetooth_gnss.MOCK_LOCATION_PROVIDER";

    public static boolean is_mock_location_enabled(Context context, int app_uid, String app_id_string)
    {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        boolean mock_enabled = false;
        try {

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Log.d(TAG,"is_mock_location_enabled Build.VERSION.SDK_INT >= Build.VERSION_CODES.M");
                AppOpsManager opsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
                mock_enabled = (opsManager.checkOp(AppOpsManager.OPSTR_MOCK_LOCATION, app_uid, app_id_string)== AppOpsManager.MODE_ALLOWED);
            } else {
                // in marshmallow this will always return true
                Log.d(TAG,"is_mock_location_enabled older models");
                mock_enabled = !android.provider.Settings.Secure.getString(context.getContentResolver(), "mock_location").equals("0");
            }
        } catch(Exception e) {
            Log.d(TAG, "check mock_enabled exception: "+Log.getStackTraceString(e));
        }
        Log.d(TAG,"is_mock_location_enabled ret "+mock_enabled);
        return mock_enabled;
    }

    private void setMock(double latitude, double longitude, double altitude, float accuracy) {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationManager.addTestProvider (LocationManager.GPS_PROVIDER,
                "requiresNetwork" == "",
                "requiresSatellite" == "",
                "requiresCell" == "",
                "hasMonetaryCost" == "",
                "supportsAltitude" == "",
                "supportsSpeed" == "",
                "supportsBearing" == "",
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE);

        Location newLocation = new Location(LocationManager.GPS_PROVIDER);

        newLocation.setLatitude(latitude);
        newLocation.setLongitude(longitude);
        newLocation.setAccuracy(accuracy);
        newLocation.setAltitude(altitude);
        newLocation.setAccuracy(accuracy);
        newLocation.setTime(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            newLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);

        locationManager.setTestProviderStatus(LocationManager.GPS_PROVIDER,
                LocationProvider.AVAILABLE,
                null,System.currentTimeMillis());

        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, newLocation);
    }


    // Binder given to clients
    private final IBinder m_binder = new LocalBinder();

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public bluetooth_gnss_service getService() {
            // Return this instance of LocalService so clients can call public methods
            return bluetooth_gnss_service.this;
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind()");
        return m_binder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");
        boolean was_connected = close();
        if (was_connected) {
            Toast.makeText(this, "Bluetooth GNSS Disconnected...", Toast.LENGTH_SHORT).show();
        }
    }
}