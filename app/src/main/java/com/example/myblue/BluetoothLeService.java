/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.myblue;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    //To tell the onCharacteristicWrite call back function that this is a new characteristic,
    //not the Write Characteristic to the device successfully.
    private static final int WRITE_NEW_CHARACTERISTIC = -1;
    //define the limited length of the characteristic.
    private static final int MAX_CHARACTERISTIC_LENGTH = 17;
    //Show that Characteristic is writing or not.
    private boolean mIsWritingCharacteristic=false;

    //class to store the Characteristic and content string push into the ring buffer.
    private class BluetoothGattCharacteristicHelper{
        BluetoothGattCharacteristic mCharacteristic;
        String mCharacteristicValue;
        BluetoothGattCharacteristicHelper(BluetoothGattCharacteristic characteristic, String characteristicValue){
            mCharacteristic=characteristic;
            mCharacteristicValue=characteristicValue;
        }
    }
    //ring buffer
    private RingBuffer<BluetoothGattCharacteristicHelper> mCharacteristicRingBuffer = new RingBuffer<BluetoothGattCharacteristicHelper>(8);

    public final static String ACTION_GATT_CONNECTED =
            "com.example.myblue.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.myblue.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.myblue.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.myblue.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.myblue.EXTRA_DATA";

    public final static UUID UUID_HEART_RATE_MEASUREMENT =
            UUID.fromString(SampleGattAttributes.HEART_RATE_MEASUREMENT);

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            System.out.println("onServicesDiscovered "+status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
        {
            //this block should be synchronized to prevent the function overloading
            synchronized(this)
            {
                //CharacteristicWrite success
                if(status == BluetoothGatt.GATT_SUCCESS)
                {
                    System.out.println("onCharacteristicWrite success:"+ new String(characteristic.getValue()));
                    if(mCharacteristicRingBuffer.isEmpty())
                    {
                        mIsWritingCharacteristic = false;
                    }
                    else
                    {
                        BluetoothGattCharacteristicHelper bluetoothGattCharacteristicHelper = mCharacteristicRingBuffer.next();
                        if(bluetoothGattCharacteristicHelper.mCharacteristicValue.length() > MAX_CHARACTERISTIC_LENGTH)
                        {
                            try {
                                bluetoothGattCharacteristicHelper.mCharacteristic.setValue(bluetoothGattCharacteristicHelper.mCharacteristicValue.substring(0, MAX_CHARACTERISTIC_LENGTH).getBytes("ISO-8859-1"));

                            } catch (UnsupportedEncodingException e) {
                                // this should never happen because "US-ASCII" is hard-coded.
                                throw new IllegalStateException(e);
                            }


                            if(mBluetoothGatt.writeCharacteristic(bluetoothGattCharacteristicHelper.mCharacteristic))
                            {
                                System.out.println("writeCharacteristic init "+new String(bluetoothGattCharacteristicHelper.mCharacteristic.getValue())+ ":success");
                            }else{
                                System.out.println("writeCharacteristic init "+new String(bluetoothGattCharacteristicHelper.mCharacteristic.getValue())+ ":failure");
                            }
                            bluetoothGattCharacteristicHelper.mCharacteristicValue = bluetoothGattCharacteristicHelper.mCharacteristicValue.substring(MAX_CHARACTERISTIC_LENGTH);
                        }
                        else
                        {
                            try {
                                bluetoothGattCharacteristicHelper.mCharacteristic.setValue(bluetoothGattCharacteristicHelper.mCharacteristicValue.getBytes("ISO-8859-1"));
                            } catch (UnsupportedEncodingException e) {
                                // this should never happen because "US-ASCII" is hard-coded.
                                throw new IllegalStateException(e);
                            }

                            if(mBluetoothGatt.writeCharacteristic(bluetoothGattCharacteristicHelper.mCharacteristic))
                            {
                                System.out.println("writeCharacteristic init "+new String(bluetoothGattCharacteristicHelper.mCharacteristic.getValue())+ ":success");
                            }else{
                                System.out.println("writeCharacteristic init "+new String(bluetoothGattCharacteristicHelper.mCharacteristic.getValue())+ ":failure");
                            }
                            bluetoothGattCharacteristicHelper.mCharacteristicValue = "";

//	            			System.out.print("before pop:");
//	            			System.out.println(mCharacteristicRingBuffer.size());
                            mCharacteristicRingBuffer.pop();
//	            			System.out.print("after pop:");
//	            			System.out.println(mCharacteristicRingBuffer.size());
                        }
                    }
                }
                //WRITE a NEW CHARACTERISTIC
                else if(status == WRITE_NEW_CHARACTERISTIC)
                {
                    if((!mCharacteristicRingBuffer.isEmpty()) && mIsWritingCharacteristic==false)
                    {
                        BluetoothGattCharacteristicHelper bluetoothGattCharacteristicHelper = mCharacteristicRingBuffer.next();
                        if(bluetoothGattCharacteristicHelper.mCharacteristicValue.length() > MAX_CHARACTERISTIC_LENGTH)
                        {

                            try {
                                bluetoothGattCharacteristicHelper.mCharacteristic.setValue(bluetoothGattCharacteristicHelper.mCharacteristicValue.substring(0, MAX_CHARACTERISTIC_LENGTH).getBytes("ISO-8859-1"));
                            } catch (UnsupportedEncodingException e) {
                                // this should never happen because "US-ASCII" is hard-coded.
                                throw new IllegalStateException(e);
                            }

                            if(mBluetoothGatt.writeCharacteristic(bluetoothGattCharacteristicHelper.mCharacteristic))
                            {
                                System.out.println("writeCharacteristic init "+new String(bluetoothGattCharacteristicHelper.mCharacteristic.getValue())+ ":success");
                            }else{
                                System.out.println("writeCharacteristic init "+new String(bluetoothGattCharacteristicHelper.mCharacteristic.getValue())+ ":failure");
                            }
                            bluetoothGattCharacteristicHelper.mCharacteristicValue = bluetoothGattCharacteristicHelper.mCharacteristicValue.substring(MAX_CHARACTERISTIC_LENGTH);
                        }
                        else
                        {
                            try {
                                bluetoothGattCharacteristicHelper.mCharacteristic.setValue(bluetoothGattCharacteristicHelper.mCharacteristicValue.getBytes("ISO-8859-1"));
                            } catch (UnsupportedEncodingException e) {
                                // this should never happen because "US-ASCII" is hard-coded.
                                throw new IllegalStateException(e);
                            }


                            if(mBluetoothGatt.writeCharacteristic(bluetoothGattCharacteristicHelper.mCharacteristic))
                            {
                                System.out.println("writeCharacteristic init "+new String(bluetoothGattCharacteristicHelper.mCharacteristic.getValue())+ ":success");
//	            	        	System.out.println((byte)bluetoothGattCharacteristicHelper.mCharacteristic.getValue()[0]);
//	            	        	System.out.println((byte)bluetoothGattCharacteristicHelper.mCharacteristic.getValue()[1]);
//	            	        	System.out.println((byte)bluetoothGattCharacteristicHelper.mCharacteristic.getValue()[2]);
//	            	        	System.out.println((byte)bluetoothGattCharacteristicHelper.mCharacteristic.getValue()[3]);
//	            	        	System.out.println((byte)bluetoothGattCharacteristicHelper.mCharacteristic.getValue()[4]);
//	            	        	System.out.println((byte)bluetoothGattCharacteristicHelper.mCharacteristic.getValue()[5]);

                            }else{
                                System.out.println("writeCharacteristic init "+new String(bluetoothGattCharacteristicHelper.mCharacteristic.getValue())+ ":failure");
                            }
                            bluetoothGattCharacteristicHelper.mCharacteristicValue = "";

//		            			System.out.print("before pop:");
//		            			System.out.println(mCharacteristicRingBuffer.size());
                            mCharacteristicRingBuffer.pop();
//		            			System.out.print("after pop:");
//		            			System.out.println(mCharacteristicRingBuffer.size());
                        }
                    }

                    mIsWritingCharacteristic = true;

                    //clear the buffer to prevent the lock of the mIsWritingCharacteristic
                    if(mCharacteristicRingBuffer.isFull())
                    {
                        mCharacteristicRingBuffer.clear();
                        mIsWritingCharacteristic = false;
                    }
                }
                else
                //CharacteristicWrite fail
                {
                    mCharacteristicRingBuffer.clear();
                    System.out.println("onCharacteristicWrite fail:"+ new String(characteristic.getValue()));
                    System.out.println(status);
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                System.out.println("onCharacteristicRead  "+characteristic.getUuid().toString());
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }
        @Override
        public void  onDescriptorWrite(BluetoothGatt gatt,
                                       BluetoothGattDescriptor characteristic,
                                       int status){
            System.out.println("onDescriptorWrite  "+characteristic.getUuid().toString()+" "+status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            //System.out.println("onCharacteristicChanged  "+ new String(characteristic.getValue()));
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);
        System.out.println("BluetoothLeService broadcastUpdate");
        // This is special handling for the Heart Rate Measurement profile.  Data parsing is
        // carried out as per profile specifications:
        // http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml
        /*if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            int flag = characteristic.getProperties();
            int format = -1;
            if ((flag & 0x01) != 0) {
                format = BluetoothGattCharacteristic.FORMAT_UINT16;
                Log.d(TAG, "Heart rate format UINT16.");
            } else {
                format = BluetoothGattCharacteristic.FORMAT_UINT8;
                Log.d(TAG, "Heart rate format UINT8.");
            }
            final int heartRate = characteristic.getIntValue(format, 1);
            Log.d(TAG, String.format("Received heart rate: %d", heartRate));
            intent.putExtra(EXTRA_DATA, String.valueOf(heartRate));
        } else {
        // For all other profiles, writes the data formatted in HEX.
        final byte[] data = characteristic.getValue();
        if (data != null && data.length > 0) {
            final StringBuilder stringBuilder = new StringBuilder(data.length);
            for (byte byteChar : data)
                stringBuilder.append(String.format("%02X ", byteChar));
            intent.putExtra(EXTRA_DATA, new String(data) + "\n" + stringBuilder.toString());
        }
    }
        sendBroadcast(intent);*/

        /* String jsonData = "[Insert JSON data here]";

        GsonBuilder gsonb = new GsonBuilder();
        Gson gson = gsonb.create();

        try
        {
            jsonData = gson.fromJson(dat, String.class);
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }*/

        final byte[] data = characteristic.getValue();
        //String dat = characteristic.getValue().toString();
        if (data!= null && data.length > 0) { //data
            intent.putExtra(EXTRA_DATA, new String(data));
            //intent.putExtra(EXTRA_DATA, data);
            //System.out.println("incoming Data: "+ intent.putExtra(EXTRA_DATA, dat));
            sendBroadcast(intent);
        }

    }

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        // This is specific to Heart Rate Measurement.
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }
}
