package com.example.iivanov.frec;

// https://inducesmile.com/android/android-camera2-api-example-tutorial/

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
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
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    private int frameCounter = 0;
    private TextureView textureView;
    private String cameraId;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final String TAG = "MainActivity";
    private Size imageDimension;
    protected CameraDevice cameraDevice;
    protected CaptureRequest.Builder captureRequestBuilder;
    protected CameraCaptureSession cameraCaptureSessions;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private Button takePictureButton;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = (TextureView) findViewById(R.id.texture);
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);

        takePictureButton = (Button) findViewById(R.id.btn_takepicture);
        assert takePictureButton != null;
        takePictureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePicture();
            }
        });

    }



    protected void takePicture() {
        if(null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Size[] jpegSizes = null;
            if (characteristics != null) {
                jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            }
            int width = 640;
            int height = 480;
            if (jpegSizes != null && 0 < jpegSizes.length) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }


            // Create the reader for the preview frames.
            ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
            //ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);


            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(reader.getSurface());
            outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));


            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
//            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            captureBuilder.addTarget(reader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            // Orientation
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
            Log.e(TAG, "pic " + Integer.toString(this.frameCounter));
            final File file = new File(Environment.getExternalStorageDirectory() + "/pic" + Integer.toString(this.frameCounter) + ".jpg");

            this.frameCounter++;

            if (this.frameCounter >= 5) {
                android.os.Process.killProcess(android.os.Process.myPid());
            }

            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
                private void save(byte[] bytes) throws IOException {
                    OutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } finally {
                        if (null != output) {
                            output.close();
                        }
                    }
                }
            };

            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);

            final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                    createCameraPreview();
                }
            };


            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                    try {
                        // Submit a request for an image to be captured by the camera device.
                        // capture(CaptureRequest request, CameraCaptureSession.CaptureCallback listener, Handler handler)
                        cameraCaptureSession.capture(captureBuilder.build(), captureListener, mBackgroundHandler);

                        // setRepeatingRequest(CaptureRequest request, CameraCaptureSession.CaptureCallback listener, Handler handler)
                        // Request endlessly repeating capture of images by this capture session.
                        //cameraCaptureSession.setRepeatingRequest(captureBuilder.build(), captureListener, mBackgroundHandler);

                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, mBackgroundHandler);


        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            // get front facing camera
            this.setUpCameraOutputs(0, 0);

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X");
    }

    @SuppressLint("LongLogTag")
    private void setUpCameraOutputs(final int width, final int height) {
        final CameraManager manager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
        try {
            SparseArray<Integer> cameraFaceTypeMap = new SparseArray<>();

            // Check the facing types of camera devices
            for (final String cameraId : manager.getCameraIdList()) {
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

            Integer num_facing_back_camera = cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_BACK);
            Integer num_facing_front_camera = cameraFaceTypeMap.get(CameraCharacteristics.LENS_FACING_FRONT);

            for (final String cameraId : manager.getCameraIdList()) {

                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                // Ilya: If front facing camera exists, we won't use back facing camera
                if (num_facing_front_camera != null && num_facing_front_camera > 0) {
                    // We don't use a back facing camera
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        continue;
                    }
                }

                this.cameraId = cameraId;
                return;
            }
        } catch (final CameraAccessException e) {
            //Timber.tag(TAG).e("Exception!", e);
        } catch (final NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the device this code runs.
            //ErrorDialog.newInstance(getString(R.string.camera_error)).show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
    }



    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened");
            cameraDevice = camera;
            createCameraPreview();
        }
        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }
        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };


    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //open your camera here
            openCamera();
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };


    protected void updatePreview() {
        if(null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }
    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
        stopBackgroundThread();
        super.onPause();
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


}
