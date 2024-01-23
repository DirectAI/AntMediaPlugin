package io.antmedia.plugin;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BICUBIC;
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;

import static org.bytedeco.ffmpeg.global.avutil.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import java.io.UnsupportedEncodingException;
import java.io.IOException;

import io.antmedia.plugin.api.IFrameListener;
import io.vertx.core.Vertx;

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.imageio.ImageIO;
import java.awt.Color;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.plugin.api.StreamParametersInfo;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.bytedeco.ffmpeg.swscale.SwsContext;

import org.bytedeco.javacpp.DoublePointer;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import java.io.OutputStream;
import java.io.OutputStreamWriter;

import org.apache.commons.lang3.exception.ExceptionUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;

public class InferenceUtils {
	protected static Logger logger = LoggerFactory.getLogger(InferenceUtils.class);
	private static BytePointer targetFrameBuffer;
	private static BytePointer rgbFrameBuffer;

	// There are runtime errors if this isn't in place.
	static {
		nu.pattern.OpenCV.loadLocally();
	}


	public static void saveRGB(BufferedImage image, String fileName, String fileFormat) {
		try {
			File file = new File(fileName);
			ImageIO.write(image, fileFormat, file);
		} catch (IOException e) {
			logger.info("Failed to write frame to local directory.");
			logger.info(ExceptionUtils.getMessage(e)); // TODO: Incorporate Logging
		}
	}

    public static AVFrame toRGB(AVFrame inFrame) {
		int format = AV_PIX_FMT_RGBA;
		SwsContext sws_ctx = null;
		sws_ctx = sws_getCachedContext(sws_ctx, inFrame.width(), inFrame.height(), inFrame.format(),
				inFrame.width(), inFrame.height(), format,
				SWS_BICUBIC, null, null, (DoublePointer)null);


		AVFrame outFrame = AVFramePool.getInstance().getAVFrame();
		int size = av_image_get_buffer_size(format, inFrame.width(), inFrame.height(), 32);
		if(rgbFrameBuffer == null) {
			rgbFrameBuffer = new BytePointer(av_malloc(size)).capacity(size);
		}
		
		av_image_fill_arrays(outFrame.data(), outFrame.linesize(), rgbFrameBuffer, format, inFrame.width(), inFrame.height(), 32);
		outFrame.format(format);
		outFrame.width(inFrame.width());
		outFrame.height(inFrame.height());

		sws_scale(sws_ctx, inFrame.data(), inFrame.linesize(),
				0, inFrame.height(), outFrame.data(), outFrame.linesize());

		outFrame.pts(inFrame.pts());
		
		
		sws_freeContext(sws_ctx);
		sws_ctx.close();
		
		return outFrame;
	}

    public static BufferedImage avfToBi(AVFrame avf) throws IOException {
		int width = avf.width();
		int height = avf.height();

		byte[] RGBAdata = new byte[width*height*4];
		avf.data(0).get(RGBAdata);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

		int k = 0;
		for(int y = 0; y < height; y++) {
			for(int x = 0; x < width; x++) {
				int r = (int)(RGBAdata[k++]& 0xFF);
				int g = (int)(RGBAdata[k++]& 0xFF);
				int b = (int)(RGBAdata[k++]& 0xFF);
				int a = (int)(RGBAdata[k++]& 0xFF);

				Color c = new Color(r, g, b);
				image.setRGB(x, y, c.getRGB());
			}
		}
		return image;
	}

	public static byte[] getRGBData(BufferedImage image) {
		
		int width = image.getWidth();
		int height = image.getHeight();
		
		byte data[] = new byte[width*height*4];
		int k = 0;
		for(int y = 0; y < height; y++) {
			for(int x = 0; x < width; x++) {
				Color c = new Color(image.getRGB(x, y));
				data[k++] = (byte)c.getRed();
				data[k++] = (byte)c.getGreen();
				data[k++] = (byte)c.getBlue();
				data[k++] = (byte)c.getAlpha();
			}
		}
		
		return data;
	}

	public static AVFrame toTargetFormat(AVFrame inFrame, int format) {
		SwsContext sws_ctx = null;
		sws_ctx = sws_getCachedContext(sws_ctx, inFrame.width(), inFrame.height(), inFrame.format(),
				inFrame.width(), inFrame.height(), format,
				SWS_BICUBIC, null, null, (DoublePointer)null);


		AVFrame outFrame = AVFramePool.getInstance().getAVFrame();
		int size = av_image_get_buffer_size(format, inFrame.width(), inFrame.height(), 32);
		if(targetFrameBuffer == null) {
			targetFrameBuffer = new BytePointer(av_malloc(size)).capacity(size);
		}
		
		av_image_fill_arrays(outFrame.data(), outFrame.linesize(), targetFrameBuffer, format, inFrame.width(), inFrame.height(), 32);
		outFrame.format(format);
		outFrame.width(inFrame.width());
		outFrame.height(inFrame.height());

		sws_scale(sws_ctx, inFrame.data(), inFrame.linesize(),
				0, inFrame.height(), outFrame.data(), outFrame.linesize());

		outFrame.pts(inFrame.pts());
		
		sws_freeContext(sws_ctx);
		sws_ctx.close();
		
		return outFrame;
	}

	public static String websocketStream(BufferedImage image, String timestamp, int videoFrameCount, WebSocketClient webSocketClient) {
		// Convert BufferedImage to TYPE_3BYTE_BGR
        BufferedImage bgrImage = new BufferedImage(image.getWidth(), image.getHeight(), image.TYPE_3BYTE_BGR);
        Graphics2D graphics = bgrImage.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
		
		// Convert BufferedImage to Mat
        Mat mat = bufferedImageToMat(bgrImage);

        // Encode the Mat as JPEG
        MatOfByte jpegFrame = new MatOfByte();
        MatOfInt encodeParams = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 80);
        boolean ret = Imgcodecs.imencode(".jpg", mat, jpegFrame, encodeParams);

        // Send the bytes of the JPEG frame through a WebSocket
        if (ret) {
            webSocketClient.sendBytes(jpegFrame.toArray());
			JSONObject metadata = new JSONObject();
			metadata.put("frame_id", videoFrameCount);
			metadata.put("timestamp", System.currentTimeMillis());
			String jsonString = metadata.toJSONString();
			webSocketClient.sendJson(jsonString);
			return "Image & Corresponding JSON @ Timestamp: " + timestamp + " and Frame: " + videoFrameCount + " have been sent through the websocket.";
        } else {
            return "Failed to encode the image.";
        }
	}

	public static Mat bufferedImageToMat(BufferedImage bufferedImage) {
        int type = CvType.CV_8UC3;
        if (bufferedImage.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            type = CvType.CV_8UC1;
        }

        Mat mat = new Mat(bufferedImage.getHeight(), bufferedImage.getWidth(), type);
        byte[] data = ((DataBufferByte) bufferedImage.getRaster().getDataBuffer()).getData();
        mat.put(0, 0, data);

        return mat;
    }

	public static BufferedImage writeRectangles(BufferedImage image, int topLeftX, int topLeftY, int bottomRightX, int bottomRightY) {
		Graphics2D g2D = image.createGraphics();
		g2D.setStroke(new BasicStroke(3));
		g2D.setColor(Color.RED);
		Rectangle rectangle = new Rectangle(topLeftX, topLeftY, bottomRightX-topLeftX, bottomRightY-topLeftY);
		g2D.draw(rectangle);
		return image;
	}

	public static String sendPostRequestWithParams(String clientWebhook, JSONObject params) {
        try {
			StringBuilder query = new StringBuilder();
			for (Object key : params.keySet()) {
				Object value = params.get(key);
				query.append(key).append("=").append(value).append("&");
			}
			String stringQuery = query.toString();
			stringQuery = stringQuery.substring(0, stringQuery.length() - 1);

			// Append query string to webhook URL
			URL url = new URL(clientWebhook + "?" + stringQuery);

            // Open a connection to the URL
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Set the request method to POST
            connection.setRequestMethod("POST");
			connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");

            // Enable output and input streams
            connection.setDoInput(true);
			
			// Read response
            int responseCode = connection.getResponseCode();
            BufferedReader inputReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = inputReader.readLine()) != null) {
                content.append(inputLine);
            }
			
			// Close connections
            inputReader.close();
            connection.disconnect();
			return content.toString();
        } catch (Exception e) {
            return ExceptionUtils.getMessage(e);
        }
    }

	public static String sendPostRequest(String clientWebhook, String message) {
        try {
            // Create a URL object with the webhook URL
            URL url = new URL(clientWebhook);

            // Open a connection to the URL
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Set the request method to POST
            connection.setRequestMethod("POST");
			connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");

            // Enable output and input streams
            connection.setDoOutput(true);
            connection.setDoInput(true);
			
			// Send JSON object in request body
            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(message);
            outputStream.flush();
            outputStream.close();
			
			// Read response
            int responseCode = connection.getResponseCode();
            BufferedReader inputReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = inputReader.readLine()) != null) {
                content.append(inputLine);
            }
			
			// Close connections
            inputReader.close();
            connection.disconnect();
			return content.toString();
        } catch (Exception e) {
            return ExceptionUtils.getMessage(e);
        }
    }

 	public static void downloadFramesToS3(AmazonS3 s3Client, BufferedImage image, String s3Bucket, String key) throws IOException {
		logger.info("writing to s3bucket: " + s3Bucket);
		logger.info("writing to s3 filepath: " + key);

		ByteArrayOutputStream os = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", os);
		byte[] buffer = os.toByteArray();
		InputStream is = new ByteArrayInputStream(buffer);
		ObjectMetadata meta = new ObjectMetadata();
		meta.setContentLength(buffer.length);
		s3Client.putObject(new PutObjectRequest(s3Bucket, key, is, meta));
	}
}
