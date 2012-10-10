package se.embargo.retroboy;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import se.embargo.core.concurrent.Parallel;
import se.embargo.core.graphics.Bitmaps;
import se.embargo.retroboy.filter.IImageFilter;
import se.embargo.retroboy.filter.YuvFilter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.hardware.Camera;
import android.hardware.Camera.ErrorCallback;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

class CameraPreview extends SurfaceView implements SurfaceHolder.Callback, Camera.PreviewCallback, ErrorCallback {
	private static final String TAG = "CameraPreview";

	private ExecutorService _threadpool = Executors.newCachedThreadPool();
	private Queue<FilterTask> _bufferpool = new ConcurrentLinkedQueue<FilterTask>();
	private long _frameseq = 0, _lastframeseq = -1;
	
	private SurfaceHolder _holder;
	
	private Camera _camera;
	private Camera.Size _previewSize;
	private Camera.CameraInfo _cameraInfo;
	
	private IImageFilter _filter;
	private Bitmaps.Transform _transform;
	
	/**
	 * Statistics for framerate calculation
	 */
	private long _framestat = 0;
	private long _laststat = 0;
	
	public CameraPreview(Context context) {
		this(context, null);
	}
	
	public CameraPreview(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}
	
	public CameraPreview(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		
		// Default filter
		_filter = new YuvFilter(480, 360, 0, true);

		// Install a SurfaceHolder.Callback so we get notified when the surface is created and destroyed.
		_holder = getHolder();
		_holder.addCallback(this);
		_holder.setType(SurfaceHolder.SURFACE_TYPE_NORMAL);
	}

	public void setCamera(Camera camera, Camera.CameraInfo cameraInfo) {
		if (_camera != null) {
			_camera.setPreviewCallbackWithBuffer(null);
		}
		
		_camera = camera;
		_cameraInfo = cameraInfo;
		
		if (_camera != null) {
			_previewSize = _camera.getParameters().getPreviewSize();
		}

		initPreview();

		if (_camera != null) {
			initTransform();
			startPreview();
		}
	}
	
	/**
	 * Restarts a paused preview
	 */
	public void initPreview() {
		if (_camera != null) {
			// Clear the buffer queue
			_camera.setPreviewCallbackWithBuffer(null);
			
			// Install this as the preview handle
			_camera.setErrorCallback(this);
			_camera.setPreviewCallbackWithBuffer(this);
			_camera.addCallbackBuffer(new byte[getBufferSize(_camera)]);
			
			// Add more buffers to increase parallelism on multicore devices
			if (Parallel.getNumberOfCores() > 1) {
				_camera.addCallbackBuffer(new byte[getBufferSize(_camera)]);	
			}
		}
	}
	
	/**
	 * Sets the active image filter
	 * @param filter	Image filter to use
	 */
	public void setFilter(IImageFilter filter) {
		_filter = filter;
		initTransform();
	}
	
	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		// data may be null if buffer was too small
		if (data != null) {
			// Submit a task to process the image
			FilterTask task = _bufferpool.poll();
			if (task != null) {
				task.init(data, camera);
			}
			else {
				task = new FilterTask(data, camera);
			}
			
			_threadpool.submit(task);
		}
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		initTransform();
	}
	
	private void startPreview() {
		if (_camera != null) {
			// Clear both the canvas buffers
			for (int i = 0; i < 2; i++) {
				Canvas canvas = _holder.lockCanvas();
				if (canvas != null) {
					canvas.drawColor(Color.BLACK);
					_holder.unlockCanvasAndPost(canvas);
				}
			}
			
			// Begin the preview
			_framestat = 0;
			_laststat = System.nanoTime();
			_camera.startPreview();
		}
	}
	
	private void initTransform() {
		if (_cameraInfo != null && _previewSize != null) {
			int width = getWidth(), height = getHeight();
			
			// Get the current device orientation
			WindowManager windowManager = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
			int rotation = windowManager.getDefaultDisplay().getRotation();
	
			// Rotate and flip the image when drawing it onto the surface
			_transform = Pictures.createTransformMatrix(
				getContext(), 
				_filter.getEffectiveWidth(_previewSize.width, _previewSize.height), 
				_filter.getEffectiveHeight(_previewSize.width, _previewSize.height), 
				_cameraInfo.facing, _cameraInfo.orientation, rotation, 
				Math.max(width, height), Math.min(width, height),
				Bitmaps.FLAG_ENLARGE);
		}
	}
	
	public static int getBufferSize(Camera camera) {
		Camera.Size size = camera.getParameters().getPreviewSize();
		int format = camera.getParameters().getPreviewFormat();
		int bits = ImageFormat.getBitsPerPixel(format);
		return size.width * size.height * bits / 8;
	}
	
	private class FilterTask implements Runnable {
		private static final String TAG = "FilterTask";
		private Camera _camera;
		private IImageFilter.ImageBuffer _buffer;
		private Paint _paint = new Paint(Paint.FILTER_BITMAP_FLAG);
		
		public FilterTask(byte[] data, Camera camera) {
			init(data, camera);
		}
		
		public void init(byte[] data, Camera camera) {
			// Check if buffer is still valid for this frame
			if (_buffer == null || _buffer.framewidth != _previewSize.width || _buffer.frameheight != _previewSize.height) {
				Log.i(TAG, "Allocating ImageBuffer for " + _previewSize.width + "x" + _previewSize.height + " pixels (" + _buffer + ")");
				_buffer = new IImageFilter.ImageBuffer(_previewSize.width, _previewSize.height);
			}
			
			// Reinitialize the buffer with the new data
			_buffer.frame = data;
			_buffer.frameseq = _frameseq++;
			_camera = camera;
		}
		
		@Override
		public void run() {
			// Filter the preview image
			_filter.accept(_buffer);
			
			// Check frame sequence number and drop out-of-sequence frames
			Canvas canvas = null;
			synchronized (this) {
				if (_lastframeseq > _buffer.frameseq) {
					// Release the buffers
					_bufferpool.offer(this);

					// Must hold canvas before releasing camera buffer or out-of-memory will result..
					synchronized (_camera) {
						_camera.addCallbackBuffer(_buffer.frame);
					}
					
					Log.w(TAG, "Dropped frame " + _buffer.frameseq + ", last frame was " + _lastframeseq);
					return;
				}
				
				_lastframeseq = _buffer.frameseq;
				canvas = _holder.lockCanvas();
			}
			
			// Must hold canvas before releasing camera buffer or out-of-memory will result..
			synchronized (_camera) {
				_camera.addCallbackBuffer(_buffer.frame);
			}
			
			// Draw and transform camera frame
			if (canvas != null) {
				canvas.drawBitmap(_buffer.bitmap, _transform.matrix, _paint);
				
				// Calculate the framerate
				if (++_framestat >= 25) {
					long ts = System.nanoTime();
					Log.i(TAG, "Framerate: " + ((double)_framestat / (((double)ts - (double)_laststat) / 1000000000d)));
					
					_framestat = 0;
					_laststat = System.nanoTime();
				}

				// Switch to next buffer
				_holder.unlockCanvasAndPost(canvas);
			}
			
			// Release the buffers
			_bufferpool.offer(this);
		}
	}

	@Override
	public void onError(int error, Camera camera) {
		Log.e(TAG, "Got camera error callback. error=" + error);
	}
}
