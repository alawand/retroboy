package se.embargo.retroboy.filter;

import java.nio.IntBuffer;

import android.graphics.Bitmap;

public interface IImageFilter {
	public class ImageBuffer {
		public byte[] data;
		public IntBuffer image;
		public Bitmap bitmap;
		public int width;
		public int height;

		public ImageBuffer(byte[] data, int width, int height) {
			this.data = data;
			this.image = IntBuffer.wrap(new int[width * height + width * 4]);
			this.bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
			this.width = width;
			this.height = height;
		}

		public ImageBuffer(int width, int height) {
			this(null, width, height);
		}
	}
	
	public void accept(ImageBuffer buffer);
}
