package se.embargo.retroboy;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import se.embargo.core.graphics.Bitmaps;
import se.embargo.retroboy.filter.IImageFilter;
import se.embargo.retroboy.filter.YuvFilter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

class CameraPreview extends SurfaceView implements SurfaceHolder.Callback, Camera.PreviewCallback {
	private ExecutorService _threadpool = Executors.newCachedThreadPool();
	private Queue<FilterTask> _bufferpool = new ConcurrentLinkedQueue<FilterTask>();
	
	private SurfaceHolder _holder;
	
	private Camera _camera;
	private Camera.Size _previewSize;
	private Camera.CameraInfo _cameraInfo;
	
	private IImageFilter _filter;
	private Bitmaps.Transform _transform;
	
	private Camera.PreviewCallback _callback;
	
	public CameraPreview(Context context) {
		this(context, null);
	}
	
	public CameraPreview(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}
	
	public CameraPreview(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		
		// Default filter
		_filter = new YuvFilter();

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

			_camera.setPreviewCallbackWithBuffer(this);
			_camera.addCallbackBuffer(new byte[getBufferSize(_camera)]);
			
			// Single buffer reduces latency when taking images without reviewing them
			//_camera.addCallbackBuffer(new byte[getBufferSize(_camera)]);
			
			// Get the current device orientation
			WindowManager windowManager = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
			int rotation = windowManager.getDefaultDisplay().getRotation();

			// Rotate/flip the image when drawing it on the surface
			_transform = Pictures.createTransformMatrix(
				getContext(), _previewSize.width, _previewSize.height, 
				cameraInfo.facing, cameraInfo.orientation, rotation, 
				_previewSize.width, _previewSize.height);
			
			startPreview();
		}
	}
	
	public void setOneShotPreviewCallback(Camera.PreviewCallback callback) {
		_callback = callback;
	}

	public void setFilter(IImageFilter filter) {
		_filter = filter;
	}
	
	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		if (_callback != null) {
			// Delegate to an additional callback
			_callback.onPreviewFrame(data, camera);
			_callback = null;
		}
		else {
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
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
	
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
			
			// Setup the camera parameters and begin the preview.
			Camera.Parameters parameters = _camera.getParameters();
			parameters.setPreviewSize(_previewSize.width, _previewSize.height);
			parameters.setPreviewFormat(ImageFormat.NV21);
			
			_camera.setParameters(parameters);
			_camera.startPreview();
		}
	}
	
	public static int getBufferSize(Camera camera) {
		Camera.Size size = camera.getParameters().getPreviewSize();
		int format = camera.getParameters().getPreviewFormat();
		int bits = ImageFormat.getBitsPerPixel(format);
		return size.width * size.height * bits / 8;
	}
	
	private class FilterTask implements Runnable {
		private Camera _camera;
		private IImageFilter.ImageBuffer _buffer;
		
		public FilterTask(byte[] data, Camera camera) {
			init(data, camera);
		}
		
		public void init(byte[] data, Camera camera) {
			_camera = camera;
			
			if (_buffer == null || _buffer.width != _previewSize.width || _buffer.height != _previewSize.height) {
				_buffer = new IImageFilter.ImageBuffer(_previewSize.width, _previewSize.height);
			}

			_buffer.data = data;
		}
		
		@Override
		public void run() {
			// Filter the preview image
			_filter.accept(_buffer);
			
			// Draw the preview image
			Canvas canvas = _holder.lockCanvas();
			canvas.drawBitmap(_buffer.bitmap, _transform.matrix, null);
			_holder.unlockCanvasAndPost(canvas);
			
			// Release the buffers
			_camera.addCallbackBuffer(_buffer.data);
			_bufferpool.offer(this);
		}
	}
}
