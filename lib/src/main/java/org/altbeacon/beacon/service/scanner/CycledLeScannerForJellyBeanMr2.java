package org.altbeacon.beacon.service.scanner;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.SystemClock;
import android.os.ParcelUuid;
import android.util.ArrayMap;
import android.util.Log;
import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;

import org.altbeacon.beacon.logging.LogManager;
import org.altbeacon.bluetooth.BluetoothCrashResolver;
import org.altbeacon.bluetooth.BluetoothUuid;
import org.altbeacon.bluetooth.Pdu;

import java.util.Map;

@TargetApi(18)
public class CycledLeScannerForJellyBeanMr2 extends CycledLeScanner {
    private static final String TAG = "CycledLeScannerForJellyBeanMr2";
    private BluetoothAdapter.LeScanCallback leScanCallback;

    public CycledLeScannerForJellyBeanMr2(Context context, long scanPeriod, long betweenScanPeriod, boolean backgroundFlag, CycledLeScanCallback cycledLeScanCallback, BluetoothCrashResolver crashResolver) {
        super(context, scanPeriod, betweenScanPeriod, backgroundFlag, cycledLeScanCallback, crashResolver);
    }

    @Override
    protected void stopScan() {
        postStopLeScan();
    }

    @Override
    protected boolean deferScanIfNeeded() {
        long millisecondsUntilStart = mNextScanCycleStartTime - SystemClock.elapsedRealtime();
        if (millisecondsUntilStart > 0) {
            LogManager.d(TAG, "Waiting to start next Bluetooth scan for another %s milliseconds",
                    millisecondsUntilStart);
            // Don't actually wait until the next scan time -- only wait up to 1 second.  This
            // allows us to start scanning sooner if a consumer enters the foreground and expects
            // results more quickly.
            if (mBackgroundFlag) {
                setWakeUpAlarm();
            }
            mHandler.postDelayed(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    scanLeDevice(true);
                }
            }, millisecondsUntilStart > 1000 ? 1000 : millisecondsUntilStart);
            return true;
        }
        return false;
    }

    @Override
    protected void startScan() {
        postStartLeScan();
    }

    @Override
    protected void finishScan() {
        postStopLeScan();
        mScanningPaused = true;
    }

    private void postStartLeScan() {
        final BluetoothAdapter bluetoothAdapter = getBluetoothAdapter();
        if (bluetoothAdapter == null) {
            return;
        }
        final BluetoothAdapter.LeScanCallback leScanCallback = getLeScanCallback();
        mScanHandler.removeCallbacksAndMessages(null);
        mScanHandler.post(new Runnable() {
            @WorkerThread
            @Override
            public void run() {
                try {
                    //noinspection deprecation
                    bluetoothAdapter.startLeScan(leScanCallback);
                } catch (Exception e) {
                    LogManager.e(e, TAG, "Internal Android exception in startLeScan()");
                }
            }
        });
    }

    private void postStopLeScan() {
        final BluetoothAdapter bluetoothAdapter = getBluetoothAdapter();
        if (bluetoothAdapter == null) {
            return;
        }
        final BluetoothAdapter.LeScanCallback leScanCallback = getLeScanCallback();
        mScanHandler.removeCallbacksAndMessages(null);
        mScanHandler.post(new Runnable() {
            @WorkerThread
            @Override
            public void run() {
                try {
                    //noinspection deprecation
                    bluetoothAdapter.stopLeScan(leScanCallback);
                } catch (Exception e) {
                    LogManager.e(e, TAG, "Internal Android exception in stopLeScan()");
                }
            }
        });
    }

    private BluetoothAdapter.LeScanCallback getLeScanCallback() {
        if (leScanCallback == null) {
            leScanCallback =
                    new BluetoothAdapter.LeScanCallback() {

                        @Override
                        public void onLeScan(final BluetoothDevice device, final int rssi, final byte[] scanRecord ) {
                            LogManager.d(TAG, "got record");
                            Map<ParcelUuid, byte[]> serviceData = parseServiceData(scanRecord);
                            mCycledLeScanCallback.onLeScan(device, rssi, scanRecord, serviceData, System.currentTimeMillis());
                            if (mBluetoothCrashResolver != null) {
                                mBluetoothCrashResolver.notifyScannedDevice(device, getLeScanCallback());
                            }
                        }
                    };
        }
        return leScanCallback;
    }

    private Map<ParcelUuid, byte[]> parseServiceData(byte[] scanRecord) {
        int currentPos = 0;
        Map<ParcelUuid, byte[]> serviceData = new ArrayMap<ParcelUuid, byte[]>();
        try {
            while (currentPos < scanRecord.length) {
                // length is unsigned int.
                int length = scanRecord[currentPos++] & 0xFF;
                if (length == 0) {
                    break;
                }
                // Note the length includes the length of the field type itself.
                int dataLength = length - 1;
                // fieldType is unsigned int.
                int fieldType = scanRecord[currentPos++] & 0xFF;
                switch (fieldType) {
                    case Pdu.SERVICE_UUID_16_BIT_PDU_TYPE:
                    case Pdu.SERVICE_UUID_32_BIT_PDU_TYPE:
                    case Pdu.SERVICE_UUID_128_BIT_PDU_TYPE:
                        int serviceUuidLength = BluetoothUuid.UUID_BYTES_16_BIT;
                        if (fieldType == Pdu.SERVICE_UUID_32_BIT_PDU_TYPE) {
                            serviceUuidLength = BluetoothUuid.UUID_BYTES_32_BIT;
                        } else if (fieldType == Pdu.SERVICE_UUID_128_BIT_PDU_TYPE) {
                            serviceUuidLength = BluetoothUuid.UUID_BYTES_128_BIT;
                        }

                        byte[] serviceDataUuidBytes = extractBytes(scanRecord, currentPos,
                                serviceUuidLength);
                        ParcelUuid serviceDataUuid = BluetoothUuid.parseUuidFrom(
                                serviceDataUuidBytes);
                        byte[] serviceDataArray = extractBytes(scanRecord,
                                currentPos + serviceUuidLength, dataLength - serviceUuidLength);
                        serviceData.put(serviceDataUuid, serviceDataArray);
                        break;
                    default:
                        // Just ignore, we don't handle such data type.
                        break;
                }
                currentPos += dataLength;
            }
            return serviceData;
        } catch (Exception e) {
            Log.e(TAG, "unable to parse scan data: " + Arrays.toString(scanRecord));
            // As the record is invalid, ignore all the parsed results for this packet
            // and return an empty record with raw scanRecord bytes in results
            return new ArrayMap<ParcelUuid, byte[]>();
        }
    }
}
