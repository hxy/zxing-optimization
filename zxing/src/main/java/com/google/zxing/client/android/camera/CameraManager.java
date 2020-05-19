/*
 * Copyright (C) 2008 ZXing authors
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

package com.google.zxing.client.android.camera;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.DecodeThreadPoolExecutor;
import com.google.zxing.client.android.camera.open.OpenCamera;
import com.google.zxing.client.android.camera.open.OpenCameraInterface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This object wraps the Camera service object and expects to be the only one talking to it. The
 * implementation encapsulates the steps needed to take preview-sized images, which are used for
 * both preview and decoding.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
@SuppressWarnings("deprecation") // camera APIs
public final class CameraManager {

  private static final String TAG = CameraManager.class.getSimpleName();

  private static final int MIN_FRAME_WIDTH = 240;
  private static final int MIN_FRAME_HEIGHT = 240;
  private static final int MAX_FRAME_WIDTH = 1200; // = 5/8 * 1920
  private static final int MAX_FRAME_HEIGHT = 675; // = 5/8 * 1080

  private final Context context;
  private final CameraConfigurationManager configManager;
  private OpenCamera camera;
  private AutoFocusManager autoFocusManager;
  private Rect framingRect;
  private Rect framingRectInPreview;
  private boolean initialized;
  private boolean previewing;
  private int requestedCameraId = OpenCameraInterface.NO_REQUESTED_CAMERA;
  private int requestedFramingRectWidth;
  private int requestedFramingRectHeight;
  /**
   * Preview frames are delivered here, which we pass on to the registered handler. Make sure to
   * clear the handler so it will only receive one message.
   */
  private final PreviewCallback previewCallback;

  public CameraManager(Context context) {
    this.context = context;
    this.configManager = new CameraConfigurationManager(context);
    previewCallback = new PreviewCallback(configManager);
  }

  /**
   * Opens the camera driver and initializes the hardware parameters.
   *
   * @param holder The surface object which the camera will draw preview frames into.
   * @throws IOException Indicates the camera driver failed to open.
   */
  public synchronized void openDriver(SurfaceHolder holder) throws IOException {
    OpenCamera theCamera = camera;
    if (theCamera == null) {
      theCamera = OpenCameraInterface.open(requestedCameraId);
      if (theCamera == null) {
        throw new IOException("Camera.open() failed to return object from driver");
      }
      camera = theCamera;
    }

    if (!initialized) {
      initialized = true;
      configManager.initFromCameraParameters(theCamera);
      if (requestedFramingRectWidth > 0 && requestedFramingRectHeight > 0) {
        setManualFramingRect(requestedFramingRectWidth, requestedFramingRectHeight);
        requestedFramingRectWidth = 0;
        requestedFramingRectHeight = 0;
      }
    }

    Camera cameraObject = theCamera.getCamera();
    Camera.Parameters parameters = cameraObject.getParameters();
    String parametersFlattened = parameters == null ? null : parameters.flatten(); // Save these, temporarily
    int radius = getFramingRect().width()/2;
    try {
      configManager.setDesiredCameraParameters(theCamera, false);
      configManager.setFocusArea(theCamera,-radius,-radius,radius,radius);
    } catch (RuntimeException re) {
      // Driver failed
      Log.w(TAG, "Camera rejected parameters. Setting only minimal safe-mode parameters");
      Log.i(TAG, "Resetting to saved camera params: " + parametersFlattened);
      // Reset:
      if (parametersFlattened != null) {
        parameters = cameraObject.getParameters();
        parameters.unflatten(parametersFlattened);
        try {
          cameraObject.setParameters(parameters);
          configManager.setDesiredCameraParameters(theCamera, true);
          configManager.setFocusArea(theCamera,-radius,-radius,radius,radius);
        } catch (RuntimeException re2) {
          // Well, darn. Give up
          Log.w(TAG, "Camera rejected even safe-mode parameters! No configuration");
        }
      }
    }
    cameraObject.setPreviewDisplay(holder);

  }

  public synchronized boolean isOpen() {
    return camera != null;
  }

  /**
   * Closes the camera driver if still in use.
   */
  public synchronized void closeDriver() {
    if (camera != null) {
      camera.getCamera().release();
      camera = null;
      // Make sure to clear these each time we close the camera, so that any scanning rect
      // requested by intent is forgotten.
      framingRect = null;
      framingRectInPreview = null;
    }
  }

  /**
   * Asks the camera hardware to begin drawing preview frames to the screen.
   */
  public synchronized void startPreview() {
    OpenCamera theCamera = camera;
    if (theCamera != null && !previewing) {
      theCamera.getCamera().startPreview();
      previewing = true;
      autoFocusManager = new AutoFocusManager(context, theCamera.getCamera());
    }
  }

  /**
   * Tells the camera to stop drawing preview frames.
   */
  public synchronized void stopPreview() {
    if (autoFocusManager != null) {
      autoFocusManager.stop();
      autoFocusManager = null;
    }
    if (camera != null && previewing) {
      camera.getCamera().setPreviewCallback(null);
      camera.getCamera().stopPreview();
      previewCallback.setThreadPoolExecutor(null);
      previewing = false;
    }
  }

  /**
   * Convenience method for {@link com.google.zxing.client.android.CaptureActivity}
   *
   * @param newSetting if {@code true}, light should be turned on if currently off. And vice versa.
   */
  public synchronized void setTorch(boolean newSetting) {
    OpenCamera theCamera = camera;
    if (theCamera != null && newSetting != configManager.getTorchState(theCamera.getCamera())) {
      boolean wasAutoFocusManager = autoFocusManager != null;
      if (wasAutoFocusManager) {
        autoFocusManager.stop();
        autoFocusManager = null;
      }
      configManager.setTorch(theCamera.getCamera(), newSetting);
      if (wasAutoFocusManager) {
        autoFocusManager = new AutoFocusManager(context, theCamera.getCamera());
        autoFocusManager.start();
      }
    }
  }

  /**
   * A single preview frame will be returned to the handler supplied. The data will arrive as byte[]
   * in the message.obj field, with width and height encoded as message.arg1 and message.arg2,
   * respectively.
   *
   */
  public synchronized void requestPreviewFrame(DecodeThreadPoolExecutor executor) {
    OpenCamera theCamera = camera;
    if (theCamera != null && previewing) {
      Log.d("decode_flow","0000");
      previewCallback.setThreadPoolExecutor(executor);
      theCamera.getCamera().setPreviewCallback(previewCallback);
    }
  }

  /**
   * Calculates the framing rect which the UI should draw to show the user where to place the
   * barcode. This target helps with alignment as well as forces the user to hold the device
   * far enough away to ensure the image will be in focus.
   *
   * @return The rectangle to draw on screen in window coordinates.
   */
  public synchronized Rect getFramingRect() {
    if (framingRect == null) {
      if (camera == null) {
        return null;
      }
      Point screenResolution = configManager.getScreenResolution();
      if (screenResolution == null) {
        // Called early, before init even finished
        return null;
      }

      int width = findDesiredDimensionInRange(screenResolution.x, MIN_FRAME_WIDTH, MAX_FRAME_WIDTH);
      int height = findDesiredDimensionInRange(screenResolution.y, MIN_FRAME_HEIGHT, MAX_FRAME_HEIGHT);

      int leftOffset = (screenResolution.x - width) / 2;
      int topOffset = (screenResolution.y - height) / 2;
      framingRect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + width);
      Log.d(TAG, "Calculated framing rect: " + framingRect);
    }
    return framingRect;
  }

  private static int findDesiredDimensionInRange(int resolution, int hardMin, int hardMax) {
    int dim = 5 * resolution / 9; // Target 5/8 of each dimension
    if (dim < hardMin) {
      return hardMin;
    }
    return Math.min(dim, hardMax);
  }

  /**
   * Like {@link #getFramingRect} but coordinates are in terms of the preview frame,
   * not UI / screen.
   *
   * @return {@link Rect} expressing barcode scan area in terms of the preview size
   */
  public synchronized Rect getFramingRectInPreview() {
    if (framingRectInPreview == null) {
      Rect framingRect = getFramingRect();
      if (framingRect == null) {
        return null;
      }
      Rect rect = new Rect(framingRect);
      Point cameraResolution = configManager.getCameraResolution();
      Point screenResolution = configManager.getScreenResolution();
      if (cameraResolution == null || screenResolution == null) {
        // Called early, before init even finished
        return null;
      }
      rect.left = (int)(rect.left / 1.05);
      rect.right = (int)(rect.right * 1.05);
      rect.top = (int)(rect.top / 1.05);
      rect.bottom = (int)(rect.bottom * 1.05);
      framingRectInPreview = rect;
    }
    return framingRectInPreview;
  }


  /**
   * Allows third party apps to specify the camera ID, rather than determine
   * it automatically based on available cameras and their orientation.
   *
   * @param cameraId camera ID of the camera to use. A negative value means "no preference".
   */
  public synchronized void setManualCameraId(int cameraId) {
    requestedCameraId = cameraId;
  }

  /**
   * Allows third party apps to specify the scanning rectangle dimensions, rather than determine
   * them automatically based on screen resolution.
   *
   * @param width The width in pixels to scan.
   * @param height The height in pixels to scan.
   */
  public synchronized void setManualFramingRect(int width, int height) {
    if (initialized) {
      Point screenResolution = configManager.getScreenResolution();
      if (width > screenResolution.x) {
        width = screenResolution.x;
      }
      if (height > screenResolution.y) {
        height = screenResolution.y;
      }
      int leftOffset = (screenResolution.x - width) / 2;
      int topOffset = (screenResolution.y - height) / 2;
      framingRect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + height);
      Log.d(TAG, "Calculated manual framing rect: " + framingRect);
      framingRectInPreview = null;
    } else {
      requestedFramingRectWidth = width;
      requestedFramingRectHeight = height;
    }
  }

  /**
   * A factory method to build the appropriate LuminanceSource object based on the format
   * of the preview buffers, as described by Camera.Parameters.
   *
   * @param data A preview frame.
   * @param width The width of the image.
   * @param height The height of the image.
   * @return A PlanarYUVLuminanceSource instance.
   */
  public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
    Rect rect = getFramingRectInPreview();
    if (rect == null) {
      return null;
    }
    if(configManager.getCameraDisplayOrientation() == 90 || configManager.getCameraDisplayOrientation() == 270){
      // Go ahead and assume it's YUV rather than die.
      return new PlanarYUVLuminanceSource(data, width, height, rect.top, rect.left,
              rect.height(), rect.width(), false);
    }else {
      // Go ahead and assume it's YUV rather than die.
      return new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
              rect.width(), rect.height(), false);
    }
  }




  private long lastZoomTime;
  private int threshold = 3;
  private int zoomMultiple = 4;
  List<ResultPoint> possibleResultPoints = new ArrayList<>();
  public void addPossibleResultPoint(ResultPoint point) {
    List<ResultPoint> points = possibleResultPoints;
    synchronized (points) {
      if(lastZoomTime == 0){lastZoomTime = System.currentTimeMillis();}
      points.add(point);
      if(System.currentTimeMillis()-lastZoomTime>=500){
        int maxDistance = calculateMaxDistance(points, threshold);
        if (maxDistance!=0 && maxDistance < getFramingRect().width()/3) {
          Camera cameraDevices = camera.getCamera();
          if (cameraDevices != null) {
            Camera.Parameters params = cameraDevices.getParameters();
            if (params.isZoomSupported()) {
              int maxZoom = params.getMaxZoom();
              int zoom = params.getZoom();
              params.setZoom(Math.min(zoom + maxZoom / zoomMultiple, maxZoom));
              cameraDevices.setParameters(params);
              threshold += 5;
              zoomMultiple += 1;
              points.clear();
            } else {
              Log.i("Zoom", "Zoom not supported");
            }
          }
        }
        lastZoomTime = System.currentTimeMillis();
        points.clear();
      }
    }
  }

  private int calculateMaxDistance(List<ResultPoint> points, int threshold){
    if(threshold<2){return 0;}
    int maxDistance = 0;
    if(points.size()>=threshold){
      int size = threshold;
      if(points.size()>=threshold*1.5){ size = (int)(threshold*1.5); }
      for(int i=0;i<size-1;i++){
        for(int j = i+1;j<size;j++){
          float distance = ResultPoint.distance(points.get(i),points.get(j));
          maxDistance = (int) Math.max(distance,maxDistance);
        }
      }
    }
    return maxDistance;
  }
}
