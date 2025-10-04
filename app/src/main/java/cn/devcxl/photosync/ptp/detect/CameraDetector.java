package cn.devcxl.photosync.ptp.detect;

import android.hardware.usb.UsbDevice;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

/**
 * Created by rainx on 2017/5/23.
 */

public class CameraDetector {

    // Select appropriate deviceInitiator, VIDs see http://www.linux-usb.org/usb.ids

    public static final int VENDOR_ID_CANON = 0x04a9;
    public static final int VENDOR_ID_NIKON = 0x04b0;
    public static final int VENDOR_ID_SONY  = 0x054c;

    public static final int VENDOR_ID_OTHER = 0xffff;

    static List<Integer> vendorIds;
    static  {
        vendorIds = Arrays.asList(
                VENDOR_ID_CANON,
                VENDOR_ID_NIKON,
                VENDOR_ID_SONY
        );
    }


    private UsbDevice device;
    public CameraDetector(UsbDevice device) {
        this.device = device;
    }


    public int getSupportedVendorId() {
        if (vendorIds.contains(device.getVendorId())) {
            return device.getVendorId();
        } else {
            return VENDOR_ID_OTHER;
        }
    }

    public String getDeviceUniqName() {
        String manufacturer = device.getManufacturerName();
        String product = device.getProductName();
        String serial;
        try {
            serial = device.getSerialNumber();
        } catch (SecurityException se) {
            // Permission not granted; fall back to vendorId/productId as a pseudo-serial
            serial = "no-permission-" + device.getVendorId() + ":" + device.getProductId();
            Log.w("CameraDetector", "No permission to read serial number; using fallback", se);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(manufacturer)
                .append("_")
                .append(product)
                .append("_")
                .append(serial);
        return sb.toString();
    }
}
