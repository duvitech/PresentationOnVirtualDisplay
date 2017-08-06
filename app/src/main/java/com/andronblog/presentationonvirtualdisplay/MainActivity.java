package com.andronblog.presentationonvirtualdisplay;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Set;

import com.six15.hud.HudResponsePacket;
import com.six15.hud.UsbService;


public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private static final int SCREEN_CAPTURE_PERMISSION_CODE = 1;
    private static final int EXTERNAL_STORAGE_PERMISSION_CODE = 2;
    private static final int CAMERA_USE_PERMISSION_CODE = 3;


    private static final int FRAMERATE = 5;
    private static final String FILENAME = Environment.getExternalStorageDirectory().getPath()+"/presentation.mp4";

    private int mWidth = 640;
    private int mHeight = 400;
    private DisplayMetrics mMetrics = new DisplayMetrics();

    private DisplayManager mDisplayManager;
    private VirtualDisplay mVirtualDisplay;

    private int mResultCode;
    private Intent mResultData;

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mProjection;
    private MediaProjection.Callback mProjectionCallback;

    private ImageView mImageView;

    private Button mButtonCreate;
    private Button mButtonDestroy;

    private ImageReader imageReader;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private boolean bHudConnected = false;
    private Handler mHandler;
    private UsbService usbService = null;
    private static boolean bRenderframe = true;

    /*
 * This function handles repsonse packets from the UsbService.
*/
    public void responsePacketHandler(HudResponsePacket resp) {
        switch (resp.getCommand()){
            case HC_STATUS:
                Log.i(TAG, "Response HC_STATUS");
                break;
            case HC_VERSIONS:
                Log.i(TAG, "Response HC_VERSIONS");
                break;
            case HC_DEV_INFO:
                Log.i(TAG, "Response HC_DEV_INFO");
                break;
            case HC_DEV_SETTINGS:
                Log.i(TAG, "Response HC_DEV_SETTINGS");
                break;
            case HC_MODE_UPD:
                Log.i(TAG, "Response HC_MODE_UPD");
                break;
            case HC_MODE_SRST:
                Log.i(TAG, "Response HC_MODE_SRST");
                break;
            case HC_DISP_SIZE:
                Log.i(TAG, "Response HC_DISP_SIZE");
                break;
            case HC_DISP_BRT:
                Log.i(TAG, "Response HC_DISP_BRT");
                break;
            case HC_DISP_ON:
                Log.i(TAG, "Response HC_DISP_ON");
                break;
            case HC_DISP_INFO:
                Log.i(TAG, "Response HC_DISP_INFO");
                break;
            case HC_CFG_SPEN:
                Log.i(TAG, "Response HC_CFG_SPEN");
                break;
            case HC_CFG_SPDEL:
                Log.i(TAG, "Response HC_CFG_SPDEL");
                break;
            case HC_CFG_SPIMAGE:
                Log.i(TAG, "Response HC_CFG_SPIMAGE");
                break;
            case HC_CFG_RESET:
                Log.i(TAG, "Response HC_CFG_RESET");
                break;
            case HC_DEV_METRICS:
                Log.i(TAG, "Response HC_DEV_METRICS");
                break;
            case HC_HEART_BEAT:
                Toast.makeText(this, "Ping Response Received", Toast.LENGTH_LONG).show();
                break;
            default:
                Log.i(TAG, "Response not handled");
                break;
        }

    }

    /*
 * Notifications from UsbService will be received here.
 */
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case UsbService.ACTION_USB_PERMISSION_GRANTED: // USB PERMISSION GRANTED
                    //Toast.makeText(context, "USB Ready", Toast.LENGTH_SHORT).show();
                    usbConnectDisplay(true);
                    break;
                case UsbService.ACTION_USB_PERMISSION_NOT_GRANTED: // USB PERMISSION NOT GRANTED
                    Toast.makeText(context, "USB Permission not granted", Toast.LENGTH_SHORT).show();
                    usbConnectDisplay(false);
                    break;
                case UsbService.ACTION_NO_USB: // NO USB CONNECTED
                    //Toast.makeText(context, "No USB connected", Toast.LENGTH_SHORT).show();
                    usbConnectDisplay(false);
                    break;
                case UsbService.ACTION_USB_DISCONNECTED: // USB DISCONNECTED
                    //Toast.makeText(context, "USB disconnected", Toast.LENGTH_SHORT).show();
                    usbConnectDisplay(false);
                    break;
                case UsbService.ACTION_USB_NOT_SUPPORTED: // USB NOT SUPPORTED
                    Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show();
                    usbConnectDisplay(false);
                    break;
            }
        }
    };

    private void usbConnectDisplay(boolean enabled) {
        if (enabled) {
            Log.i(TAG, "Darwin HUD Connected");
            bHudConnected = true;
        } else {
            Log.i(TAG, "Darwin HUD Disconnected");
            bHudConnected = false;
        }
    }

    private final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder arg1) {
            usbService = ((UsbService.UsbBinder) arg1).getService();
            usbService.setHandler(mHandler); /* ui thread handler needed by service */
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            usbService = null;
        }
    };

    private void startService(Class<?> service, ServiceConnection serviceConnection, Bundle extras) {
        if (!UsbService.SERVICE_CONNECTED) {
            Intent startService = new Intent(this, service);
            if (extras != null && !extras.isEmpty()) {
                Set<String> keys = extras.keySet();
                for (String key : keys) {
                    String extra = extras.getString(key);
                    startService.putExtra(key, extra);
                }
            }
            startService(startService);
        }
        Intent bindingIntent = new Intent(this, service);
        bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setFilters() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbService.ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(UsbService.ACTION_NO_USB);
        filter.addAction(UsbService.ACTION_USB_DISCONNECTED);
        filter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED);
        filter.addAction(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED);
        registerReceiver(mUsbReceiver, filter);
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mImageView = (ImageView) findViewById(R.id.imageView);

        // Obtain display metrics of current display to know its density (dpi)
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        display.getMetrics(mMetrics);

        // Initialize resolution of virtual display in pixels to show
        // the surface view on full screen
        mHandler = new MyHandler(this);

        mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        mButtonCreate = (Button) findViewById(R.id.btn_create_virtual_display);
        mButtonCreate.setEnabled(false);
        mButtonCreate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startScreenCapture();
            }
        });

        mButtonDestroy = (Button) findViewById(R.id.btn_destroy_virtual_display);
        mButtonDestroy.setEnabled(false);
        mButtonDestroy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopScreenCapture();
            }
        });

        // Check if we have write permission
        int permission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Write permissions is not granted");
            // Request permissions
            ActivityCompat.requestPermissions(this,
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    EXTERNAL_STORAGE_PERMISSION_CODE);
        } else {
            Log.i(TAG, "Camera permission is granted!");
            mButtonCreate.setEnabled(true);
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch(requestCode) {
            case EXTERNAL_STORAGE_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Write permission is granted!");
                    mButtonCreate.setEnabled(true);
                } else {
                    Toast.makeText(this, "Write permission is not granted", Toast.LENGTH_LONG).show();
                }
                return;
            }

        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        setFilters();
        startService(UsbService.class, usbConnection, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        destroyVirtualDisplay();
        unregisterReceiver(mUsbReceiver);
        unbindService(usbConnection);

    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        if (mProjection != null) {
            Log.i(TAG, "Stop media projection");
            mProjection.unregisterCallback(mProjectionCallback);
            mProjection.stop();
            mProjection = null;
        }

    }

    private void startScreenCapture() {
        if (mProjection != null) {
            // start virtual display
            Log.i(TAG, "The media projection is already gotten");
            createVirtualDisplay();
        } else if (mResultCode != 0 && mResultData != null) {
            // get media projection
            Log.i(TAG, "Get media projection with the existing permission");
            mProjection = getProjection();
            createVirtualDisplay();
        } else {
            Log.i(TAG, "Request the permission for media projection");
            startActivityForResult(mProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_PERMISSION_CODE);
        }
    }

    private void stopScreenCapture() {
        destroyVirtualDisplay();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        mResultCode = resultCode;
        mResultData = data;
        if (requestCode != SCREEN_CAPTURE_PERMISSION_CODE) {
            Toast.makeText(this, "Unknown request code: " + requestCode, Toast.LENGTH_SHORT).show();
            return;
        }
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, "Screen Cast Permission Denied", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(TAG, "Get media projection with the new permission");
        mProjection = getProjection();
        createVirtualDisplay();
    }

    private MediaProjection getProjection() {
        MediaProjection projection = mProjectionManager.getMediaProjection(mResultCode, mResultData);
        // Add a callback to be informed if the projection
        // will be stopped from the status bar.
        mProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection.Callback onStop obj:" + toString());
                destroyVirtualDisplay();
                mProjection = null;
            }
        };
        projection.registerCallback(mProjectionCallback, null);
        return projection;
    }

    private static int count = 0;
    public ImageReader.OnImageAvailableListener hudImageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            ByteBuffer buffer;
            Image image = reader.acquireLatestImage();
            if (image == null)
                return;

            final Bitmap bmp = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
            buffer = image.getPlanes()[0].getBuffer();
            bmp.copyPixelsFromBuffer(buffer);

            if(bRenderframe) {
                if (usbService != null && bHudConnected) {
                    usbService.sendImageToHud(bmp);
                }
            }

/*
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mImageView.setImageBitmap(bmp);
                }
            });
*/
            image.close();
            if(count == 0 && bRenderframe) {
                bRenderframe = !bRenderframe;
                count++;
            }else{
                if(count >= 5 ){
                    bRenderframe = true;
                    count = 0;
                }else{
                    count++;
                }
            }
        }
    };

    private void createVirtualDisplay() {
        if (mProjection != null && mVirtualDisplay == null) {
            Log.d(TAG, "createVirtualDisplay WxH (px): " + mWidth + "x" + mHeight +
                    ", dpi: " + mMetrics.densityDpi);

            mBackgroundThread = new HandlerThread("Virtual Display Background");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
            imageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBX_8888, FRAMERATE);
            Surface readerSurface = imageReader.getSurface();

            //flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;

            mVirtualDisplay = mProjection.createVirtualDisplay("MyVirtualDisplay",
                    mWidth, mHeight, mMetrics.densityDpi, flags, readerSurface,
                    null /*Callbacks*/, null /*Handler*/);

            mButtonCreate.setEnabled(false);
            mButtonDestroy.setEnabled(true);

            // Start recording the content of MediaRecorder surface rendering by VirtualDisplay
            // into file.

            imageReader.setOnImageAvailableListener(hudImageListener, mBackgroundHandler);
        }
    }

    private void destroyVirtualDisplay() {
        Log.d(TAG, "destroyVirtualDisplay");
        if (mVirtualDisplay != null) {
            Log.d(TAG, "destroyVirtualDisplay release");
            mVirtualDisplay.release();
            mVirtualDisplay = null;

            if(mBackgroundThread != null) {
                mBackgroundThread.quitSafely();
                try {
                    mBackgroundThread.join();
                    mBackgroundThread = null;
                    mBackgroundHandler = null;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        mButtonDestroy.setEnabled(false);
        mButtonCreate.setEnabled(true);
    }

    /*
     * This handler will be passed to UsbService. Data received from serial port is displayed through this handler
     */
    private static class MyHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;

        public MyHandler(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UsbService.MESSAGE_FROM_SERIAL_PORT:
                    String data = (String) msg.obj;
                    if(data.length() == 1){
                        if(data.toCharArray()[0] == 0x00){
                        /* success */

                        }else{
                        /* error */
                            Log.d(TAG, "received data");

                        }
                    }else if(data.length() > 1){
                        Log.d(TAG, "received data " + data);
                    }

                    break;
                case UsbService.CTS_CHANGE:
                    Toast.makeText(mActivity.get(), "CTS_CHANGE",Toast.LENGTH_LONG).show();
                    break;
                case UsbService.DSR_CHANGE:
                    Toast.makeText(mActivity.get(), "DSR_CHANGE",Toast.LENGTH_LONG).show();
                    break;
                case UsbService.SYNC_READ:
                /* com.six15.hud.HudResponsePacket */
                    // Log.d(TAG, "Class: " + msg.obj.getClass().toString());

                    HudResponsePacket p = (HudResponsePacket) msg.obj;
                    mActivity.get().responsePacketHandler(p);

                    break;
            }
        }
    }

    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {

        private boolean mNewDisplayAdded = false;
        private int mCurrentDisplayId = -1;
        private DemoPresentation mPresentation;

        @Override
        public void onDisplayAdded(int i) {
            Log.d(TAG, "onDisplayAdded id=" + i);
            if (!mNewDisplayAdded && mCurrentDisplayId == -1) {
                mNewDisplayAdded = true;
                mCurrentDisplayId = i;
            }
        }

        @Override
        public void onDisplayRemoved(int i) {
            Log.d(TAG, "onDisplayRemoved id=" + i);
            if (mCurrentDisplayId == i) {
                mNewDisplayAdded = false;
                mCurrentDisplayId = -1;
                if (mPresentation != null) {
                    mPresentation.dismiss();
                    mPresentation = null;
                }
            }
        }

        @Override
        public void onDisplayChanged(int i) {
            Log.d(TAG, "onDisplayChanged id=" + i);
            if (mCurrentDisplayId == i) {
                if (mNewDisplayAdded) {
                    // create a presentation
                    mNewDisplayAdded = false;
                    Display display = mDisplayManager.getDisplay(i);
                    mPresentation = new DemoPresentation(MainActivity.this, display);
                    mPresentation.show();
                }
            }
        }


    };


}
