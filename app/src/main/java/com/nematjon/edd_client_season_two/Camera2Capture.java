package com.nematjon.edd_client_season_two;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.RectF;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;


import androidx.core.app.ActivityCompat;

import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

public class Camera2Capture {


    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private Context mContext;

    public Camera2Capture(Context context) {
        this.mContext = context;
    }


    public void setupCamera2() {
        CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);

        try {

            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                this.cameraId = cameraId;
                imageReader = ImageReader.newInstance(1080, 1440, ImageFormat.JPEG, 2); // 3 x 4 aspect ratio
                imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
            }
            openCamera2();
        } catch (CameraAccessException | NullPointerException e) {
            e.printStackTrace();
        }
    }


    private void openCamera2() {
        CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {

            if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                //todo request permissions
                return;
            }
            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice device) {
            cameraDevice = device;
            createCaptureSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
        }
    };

    private void createCaptureRequest() {
        try {

            CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            requestBuilder.addTarget(imageReader.getSurface());

            // Focus
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            // Orientation
            requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, 270); //get proper rotation


            cameraCaptureSession.capture(requestBuilder.build(), null, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private void createCaptureSession() {
        List<Surface> outputSurfaces = new LinkedList<>();
        outputSurfaces.add(imageReader.getSurface());

        try {

            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    cameraCaptureSession = session;
                    createCaptureRequest();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader mImageReader) {

            Log.e("TAG", "processImage: ONIMAGEAVAILABLE");
            ByteBuffer buffer;
            byte[] bytes;
            File file = new File(mContext.getExternalFilesDir("Photos") + File.separator + System.currentTimeMillis() + ".jpg"); // saves images to the app folder
            FileOutputStream output = null;

            Image image = mImageReader.acquireNextImage();
            buffer = image.getPlanes()[0].getBuffer();
            bytes = new byte[buffer.remaining()]; // makes byte array large enough to hold image
            buffer.get(bytes); // copies image from buffer to byte array
            try {
                output = new FileOutputStream(file);
                output.write(bytes);    // write the byte array to file
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                image.close(); // close this to free up buffer for other images
                Log.e("TAG", "processImage: DONE SAVING");
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
            }

            cropFace(bytes, mContext);
        }


    };


    public void cropFace(byte[] byteArrayImage, Context mContext) {

        File file;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        OutputStream ous;

        Bitmap tempBitmap = BitmapFactory.decodeByteArray(byteArrayImage, 0, byteArrayImage.length);
        Bitmap rotatedBitmap = Tools.rotateBitmap(tempBitmap, 270);


        FaceDetector detector = new FaceDetector.Builder(mContext)
                .setTrackingEnabled(false).setClassificationType(1).
                        build();

        if (!detector.isOperational()) {
            Log.e("TAG", "cropFace: Could not set up face detector");
        } else {
            Frame frame = new Frame.Builder().setBitmap(rotatedBitmap).build();
            SparseArray<Face> faces = detector.detect(frame);
            Bitmap faceBitmap = null;


            Log.e("TAG", "cropFace: Number of faces detected: " + faces.size());


            for (int i = 0; i < faces.size(); i++) {

                Face thisFace = faces.valueAt(i);
                float x1 = thisFace.getPosition().x;
                float y1 = thisFace.getPosition().y;

                // detection of smiling probability
                detectAndSubmitSmilingResult(thisFace);

                // cropping the face
                faceBitmap = Bitmap.createBitmap(rotatedBitmap, Math.round(x1), Math.round(y1), Math.round(thisFace.getWidth() - 2), Math.round(thisFace.getHeight() - 2)); // 2 is some margin for cases when face is big

                // saving the cropped face
                file = new File(mContext.getExternalFilesDir("Cropped Faces") + File.separator + System.currentTimeMillis() + ".jpg"); // saves images to the app folder

                faceBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                byte[] faceByteArray = stream.toByteArray();
                faceBitmap.recycle();

                try {
                    ous = new FileOutputStream(file);
                    ous.write(faceByteArray);
                    Log.e("TAG", "cropFace: Cropped face saved");
                    ous.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }


            }
        }


    }

    public void detectAndSubmitSmilingResult (Face face){

        float smile;
        smile = face.getIsSmilingProbability();
        Log.e("SMILE", "onClick: SMILE: " + smile);

    }
}

//todo: release and close problem
//todo: problem with permissions
