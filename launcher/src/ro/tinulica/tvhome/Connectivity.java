package ro.tinulica.tvhome;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wi-Fi networks and Bluetooth devices for the Control Center and Settings. TV Home targets
 * API 28 so that, like the system settings, it may join a network itself (later targets may not).
 */
@SuppressWarnings("deprecation")
final class Connectivity {

    private Connectivity() {
    }

    // ------------------------------------------------------------------ Wi-Fi

    static final class Network {
        String ssid;
        int level;          // 0..4
        boolean secure;
        boolean current;
        int savedId = -1;
    }

    static String currentSsid(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
            String ssid = info != null ? info.getSSID() : null;
            if (ssid == null || ssid.contains("unknown") || ssid.equals("0x")) {
                return null;
            }
            return ssid.replace("\"", "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Asks for a fresh scan; results arrive a few seconds later through networks(). */
    static void scan(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifi.startScan();
            }
        } catch (RuntimeException ignored) {
            // Throttled or not allowed: the last results are still there.
        }
    }

    /** Visible networks, one per name, strongest first, the one in use on top. */
    static List<Network> networks(Context context) {
        List<Network> out = new ArrayList<>();
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return out;
        }
        String current = currentSsid(context);
        Map<String, Integer> saved = new HashMap<>();
        try {
            List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
            if (configs != null) {
                for (WifiConfiguration c : configs) {
                    if (c.SSID != null) {
                        saved.put(c.SSID.replace("\"", ""), c.networkId);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Not visible to us.
        }
        Map<String, Network> best = new LinkedHashMap<>();
        try {
            List<ScanResult> results = wifi.getScanResults();
            if (results != null) {
                for (ScanResult r : results) {
                    if (r.SSID == null || r.SSID.isEmpty()) {
                        continue;
                    }
                    int level = WifiManager.calculateSignalLevel(r.level, 5);
                    Network n = best.get(r.SSID);
                    if (n == null || level > n.level) {
                        n = n == null ? new Network() : n;
                        n.ssid = r.SSID;
                        n.level = level;
                        String caps = r.capabilities == null ? "" : r.capabilities;
                        n.secure = caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE");
                        best.put(r.SSID, n);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // No location permission: only the current network is known.
        }
        if (current != null && !best.containsKey(current)) {
            Network n = new Network();
            n.ssid = current;
            n.level = 4;
            n.secure = true;
            best.put(current, n);
        }
        for (Network n : best.values()) {
            n.current = n.ssid.equals(current);
            Integer id = saved.get(n.ssid);
            n.savedId = id == null ? -1 : id;
            out.add(n);
        }
        Collections.sort(out, (a, b) -> a.current != b.current ? (a.current ? -1 : 1) : b.level - a.level);
        return out;
    }

    /** Joins a network: a saved one by id, an open one directly, a protected one with the password. */
    static boolean connect(Context context, Network network, String password) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return false;
        }
        try {
            int id = network.savedId;
            if (id < 0) {
                WifiConfiguration config = new WifiConfiguration();
                config.SSID = "\"" + network.ssid + "\"";
                if (network.secure) {
                    if (password == null || password.isEmpty()) {
                        return false;
                    }
                    config.preSharedKey = "\"" + password + "\"";
                } else {
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                }
                id = wifi.addNetwork(config);
            }
            if (id < 0) {
                return false;
            }
            boolean ok = wifi.enableNetwork(id, true);
            wifi.reconnect();
            return ok;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Joins a network from a screen: saved and open networks at once, protected ones after
     * asking for the password with the TV keyboard. `done` runs on the main thread afterwards.
     */
    static void join(final android.app.Activity activity, final Network network, final Runnable done) {
        if (network.current) {
            return;
        }
        if (network.savedId >= 0 || !network.secure) {
            report(activity, network, connect(activity, network, null), done);
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(activity);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setHint("Parola");
        new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(network.ssid)
                .setView(input)
                .setPositiveButton("Conectează", (dialog, which) ->
                        report(activity, network, connect(activity, network, input.getText().toString()), done))
                .setNegativeButton("Renunță", null)
                .show();
        input.requestFocus();
    }

    private static void report(android.app.Activity activity, Network network, boolean ok, Runnable done) {
        SourcesService.say(activity, ok ? "Se conectează la " + network.ssid + "…"
                : "Nu m-am putut conecta la " + network.ssid);
        if (done != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(done, 4000);
        }
    }

    static String bars(int level) {
        StringBuilder sb = new StringBuilder();
        String[] glyphs = {"▂", "▄", "▆", "█"};
        for (int i = 0; i < 4; i++) {
            sb.append(i < level ? glyphs[i] : "·");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Bluetooth

    static final class Device {
        String name;
        String address;
        boolean bonded;
        boolean connected;
        String kind;
        BluetoothDevice device;
    }

    static boolean bluetoothOn() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    /** Paired devices, connected ones first. */
    static List<Device> paired() {
        List<Device> out = new ArrayList<>();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return out;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    out.add(describe(d, true));
                }
            }
        } catch (RuntimeException ignored) {
            // No permission.
        }
        Collections.sort(out, (a, b) -> a.connected != b.connected ? (a.connected ? -1 : 1)
                : String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
        return out;
    }

    static Device describe(BluetoothDevice d, boolean bonded) {
        Device out = new Device();
        out.device = d;
        out.address = d.getAddress();
        String name = null;
        try {
            name = d.getName();
        } catch (RuntimeException ignored) {
            // Unnamed.
        }
        out.name = name == null || name.isEmpty() ? d.getAddress() : name;
        out.bonded = bonded;
        try {
            // Hidden but stable since Android 4.4; tells whether any profile is connected.
            Object connected = d.getClass().getMethod("isConnected").invoke(d);
            out.connected = Boolean.TRUE.equals(connected);
        } catch (Exception ignored) {
            out.connected = false;
        }
        out.kind = kind(d);
        return out;
    }

    private static String kind(BluetoothDevice d) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            int major = c != null ? c.getMajorDeviceClass() : 0;
            switch (major) {
                case BluetoothClass.Device.Major.AUDIO_VIDEO:
                    return "Audio";
                case BluetoothClass.Device.Major.PERIPHERAL:
                    return "Telecomandă sau tastatură";
                case BluetoothClass.Device.Major.PHONE:
                    return "Telefon";
                case BluetoothClass.Device.Major.COMPUTER:
                    return "Computer";
                default:
                    return "Dispozitiv";
            }
        } catch (RuntimeException e) {
            return "Dispozitiv";
        }
    }

    interface FoundListener {
        void found(Device device);
    }

    /** Looks for nearby devices for about 12 seconds; call the returned Runnable to stop. */
    static Runnable discover(Context context, final FoundListener listener) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return () -> { };
        }
        final Context app = context.getApplicationContext();
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && d.getBondState() != BluetoothDevice.BOND_BONDED) {
                    listener.found(describe(d, false));
                }
            }
        };
        app.registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
        try {
            adapter.startDiscovery();
        } catch (RuntimeException ignored) {
            // Not allowed.
        }
        return () -> {
            try {
                adapter.cancelDiscovery();
            } catch (RuntimeException ignored) {
                // Already stopped.
            }
            try {
                app.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        };
    }

    /** Starts pairing; the system shows its own PIN / confirmation dialog when needed. */
    static boolean pair(Device device) {
        try {
            return device.device.createBond();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
