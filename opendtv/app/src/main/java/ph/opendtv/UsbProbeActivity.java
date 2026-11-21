package ph.opendtv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;

/**
 * Milestone 1: find the tuner (048d:275a), ask for USB permission,
 * open it, and print its interfaces and endpoints.
 */
public class UsbProbeActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "ph.opendtv.PROBE_PERMISSION";

    private UsbManager usbManager;
    private TextView logView;
    private UsbDeviceConnection connection;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) {
                openAndDump(device);
            } else {
                log("USB permission denied.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        logView.setPadding(24, 24, 24, 24);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        setContentView(scroll);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, filter);
        }

        findTuner();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(permissionReceiver);
        if (connection != null) connection.close();
    }

    private void findTuner() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        log("USB devices attached: " + devices.size());
        for (UsbDevice d : devices.values()) {
            log(String.format("  %04x:%04x  %s", d.getVendorId(), d.getProductId(), d.getDeviceName()));
            if (ph.opendtv.source.UsbTunerSource.isSupported(d)) {
                requestPermission(d);
                return;
            }
        }
        log("Tuner " + ph.opendtv.source.UsbTunerSource.SUPPORTED_IDS + " not found. Plug it in (OTG) and reopen the app.");
    }

    private void requestPermission(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            openAndDump(device);
            return;
        }
        // Must be MUTABLE: the system fills in the device/permission extras.
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
        usbManager.requestPermission(device, pi);
    }

    private void openAndDump(UsbDevice device) {
        connection = usbManager.openDevice(device);
        if (connection == null) {
            log("openDevice() failed.");
            return;
        }
        log("Opened. fd=" + connection.getFileDescriptor());
        log("Manufacturer: " + device.getManufacturerName());
        log("Product:      " + device.getProductName());
        log("Interfaces:   " + device.getInterfaceCount());

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            log(String.format("\nInterface %d (class=0x%02x alt=%d)",
                    intf.getId(), intf.getInterfaceClass(), intf.getAlternateSetting()));
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                log(String.format("  EP 0x%02x  %-9s %-3s maxPacket=%d",
                        ep.getAddress(), typeName(ep.getType()),
                        ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT",
                        ep.getMaxPacketSize()));
            }
        }
        log("\nRunning command-channel probe (chip id, register script, TS read)...");
        final UsbDevice dev = device;
        new Thread(new Runnable() {
            @Override
            public void run() {
                log(new ph.opendtv.source.UsbTunerSource(usbManager, dev).probeReport());
            }
        }, "UsbProbe").start();
    }

    private static String typeName(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_BULK:      return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL:   return "CONTROL";
            case UsbConstants.USB_ENDPOINT_XFER_INT:       return "INTERRUPT";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC:      return "ISOC";
            default:                                       return "?";
        }
    }

    private void log(String line) {
        runOnUiThread(() -> logView.append(line + "\n"));
    }
}
