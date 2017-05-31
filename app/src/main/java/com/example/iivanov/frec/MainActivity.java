package com.example.iivanov.frec;

// https://inducesmile.com/android/android-camera2-api-example-tutorial/

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import timber.log.Timber;
import static android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW;


/*

-Set up preview View (SurfaceView or TextureView), set its size to be the desired preview resolution.

-Create ImageReader with YUV_420_888 format and the desired recording resolution. Connect a listener to it.

-Open the camera device (can be done in parallel with the previous steps)

-Get a Surface from the both the View and the ImageReader, and use them both to create a camera capture session

-Once the session is created, create a capture request builder with TEMPLATE_RECORDING (to optimize the settings
for a recording use case), and add both the Surfaces as targets for the request

-Build the request and set it as the repeating request.

-The camera will start pushing buffers into both the preview and the ImageReader. You'll get a onImageAvailable
callback whenever a new frame is ready. Acquire the latest Image from the ImageReader's queue, get
the three ByteBuffers that make up the YCbCr image, and pass them through JNI to your native code.

-Once done with processing an Image, be sure to close it. For efficiency, there's a fixed number of
Images in the ImageReader, and if you don't return them, the camera will stall since it will
have no buffers to write to. If you need to process multiple frames in parallel, you may need
to increase the ImageReader constructor's maxImages argument.
 */


public class MainActivity extends AppCompatActivity {
    private static final int MINIMUM_PREVIEW_SIZE = 320;
    private int frameCounter = 0;
    private TextureView textureView;
    private String cameraId;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final String TAG = "MainActivity";
    private Size imageDimension;
    private CameraCaptureSession captureSession;
    protected CameraDevice cameraDevice;
    protected CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private Button takePictureButton;
    private ImageReader previewReader;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private final OnGetImageListener mOnGetPreviewListener = new OnGetImageListener();
    private Handler backgroundHandler;


    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            Log.d(TAG, "------------------------------TextureView.onSurfaceTextureAvailable()");
            openCamera(width, height);
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            Log.d(TAG, "------------------------------TextureView.onSurfaceTextureSizeChanged()");
            configureTransform(width, height);
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
    };

    //android.hardware.camera2.CameraDevice.StateCallback is called when CameraDevice changes its state.
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cd) {
            //This is called when the camera is opened
            Log.d(TAG, "-------------------------------------CameraDevice.stateCallback.onOpened");
            cameraOpenCloseLock.release();
            cameraDevice = cd;
            createCameraPreviewSession();
        }
        @Override
        public void onDisconnected(CameraDevice cd) {
            cameraOpenCloseLock.release();
            cd.close();
            cameraDevice = null;

            if (mOnGetPreviewListener != null) {
                mOnGetPreviewListener.deInitialize();
            }
        }
        @Override
        public void onError(CameraDevice cd, int error) {
            cameraOpenCloseLock.release();
            cd.close();
            cameraDevice = null;
            if (mOnGetPreviewListener != null) {
                mOnGetPreviewListener.deInitialize();
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = (TextureView) findViewById(R.id.texture);
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);


    }



    private void openCamera(int width, int height) {
        Log.d(TAG, "------------------------------openCamera()");

        // get front facing camera
        this.setUpCameraOutputs(width, height);
        this.configureTransform(width, height);

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(this.cameraId, this.stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            Timber.tag(TAG).e("Exception!", e);
        }
    }


    private void setUpCameraOutputs(final int width, final int height) {
        Log.d(TAG, "------------------------------setUpCameraOutputs()");

        final CameraManager manager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
        try {
            SparseArray<Integer> cameraFaceTypeMap = new SparseArray<>();

            // Check the facing types of camera devices
            for (String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    if (cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) != null) {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_FRONT, cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) + 1);
                    } else {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_FRONT, 1);
                    }
                }
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    if (cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT) != null) {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_BACK, cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_BACK) + 1);
                    } else {
                        cameraFaceTypeMap.append(CameraCharacteristics.LENS_FACING_BACK, 1);
                    }
                }
            }

            Integer num_facing_front_camera = cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT);

            for (String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                // Ilya: If front facing camera exists, we won't use back facing camera
                if (num_facing_front_camera != null && num_facing_front_camera > 0) {
                    // We don't use a back facing camera
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        continue;
                    }
                }

                final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }
                // For still image captures, we use the largest available size.
                final Size largest = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)), new CompareSizesByArea());
                // Attempting to use too large a preview size could  exceed the camera bus' bandwidth limitation,
                // resulting in gorgeous previews but the storage of garbage capture data.
                this.imageDimension = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, largest);
                // We fit the aspect ratio of TextureView to the size of preview we picked.
//                final int orientation = getResources().getConfiguration().orientation;
//                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
//                    textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
//                } else {
//                    textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
//                }


                this.cameraId = cameraId;
                return;
            }
        } catch (final CameraAccessException e) {
            e.printStackTrace();
        }
    }



    /** Creates a new CameraCaptureSession for camera preview */
    protected void createCameraPreviewSession() {
        Log.d(TAG, "------------------------------createCameraPreviewSession()");

        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);
            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice.createCaptureRequest(TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            // Create the reader for the preview frames.
            previewReader = ImageReader.newInstance(this.imageDimension.getWidth(), this.imageDimension.getHeight(), ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(mOnGetPreviewListener, backgroundHandler);
            previewRequestBuilder.addTarget(previewReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice.createCaptureSession(Arrays.asList(surface, previewReader.getSurface()), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) { return; }
                    // When the session is ready, we start displaying the preview.
                    captureSession = cameraCaptureSession;
                    try {
                        // Auto focus should be continuous for camera preview.
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        // Flash is automatically enabled when necessary.
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                        // Finally, we start displaying the camera preview.
                        previewRequest = previewRequestBuilder.build();
                        captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
                    } catch (final CameraAccessException e) {
                        Timber.tag(TAG).e("Exception!", e);
                    }
                }


                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            Timber.tag(TAG).e("Exception!", e);
        }
    }


    private void configureTransform(final int width, final int height) {
        Log.d(TAG, "------------------------------configureTransform()");

        int rotation = this.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, width, height);
        RectF bufferRect = new RectF(0, 0, this.imageDimension.getHeight(), this.imageDimension.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) height / this.imageDimension.getHeight(), (float) width / this.imageDimension.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(180 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(90, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }



    @Override
    protected void onResume() {
        Log.d(TAG, "---------------------------------------MainActivity.onResume");
        super.onResume();
        this.startBackgroundThread();
        // When the screen is turned off and turned back on, the SurfaceTexture is already available, and "onSurfaceTextureAvailable" will not be called.
        // In that case, we can open a camera and start preview from here (otherwise, we wait until the surface is ready in the SurfaceTextureListener).
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }


    @Override
    protected void onPause() {
        Log.d(TAG, "--------------------------------------------onPause");
        stopBackgroundThread();
        super.onPause();
    }


    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(final CameraCaptureSession session, final CaptureRequest request, final CaptureResult partialResult) {     }
        @Override
        public void onCaptureCompleted(final CameraCaptureSession session, final CaptureRequest request, final TotalCaptureResult result) {   }
    };


    /**
     * Given choices of Sizes supported by a camera, chooses the smallest one whose width and height are at least as large
     * as the respective requested values, and whose aspect ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal Size or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(final Size[] choices, final int width, final int height, final Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        final List<Size> bigEnough = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.getHeight() >= MINIMUM_PREVIEW_SIZE && option.getWidth() >= MINIMUM_PREVIEW_SIZE) {
                Timber.tag(TAG).i("Adding size: " + option.getWidth() + "x" + option.getHeight());
                bigEnough.add(option);
            } else {
                Timber.tag(TAG).i("Not adding size: " + option.getWidth() + "x" + option.getHeight());
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
            Timber.tag(TAG).i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
            return chosenSize;
        } else {
            Timber.tag(TAG).e("Couldn't find any suitable preview size");
            return choices[0];
        }
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }


    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    /** Compares two {@code Size}s based on their areas. */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }



}
