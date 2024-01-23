package io.antmedia.plugin;

import java.io.IOException;

import io.antmedia.plugin.api.IFrameListener;
import io.vertx.core.Vertx;

import static org.bytedeco.ffmpeg.global.avutil.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.util.ArrayList;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.plugin.api.StreamParametersInfo;

import org.apache.commons.lang3.exception.ExceptionUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.client.builder.AwsClientBuilder;

public class InferenceFrameListener implements IFrameListener{

	protected static Logger logger = LoggerFactory.getLogger(InferenceFrameListener.class);
	public static final String DATE_TIME_PATTERN = "yyyy-MM-dd_HH-mm-ss.SSS";
	private int videoFrameCount = 0;
	private Vertx vertx = null;
	private boolean enableTiming = true;

	private long lastCallTime;
	private File detectionFolder;
	private AVFrame yuvFrame;
	private String streamId;
	private String accessToken;

	private InferenceParameters params;
	private WebSocketClient webSocketClient;
	private String trackerInstanceId;

	private AmazonS3 s3Client;

	public InferenceFrameListener(
		Vertx vertx, 
		AntMediaApplicationAdapter app, 
		String streamId,
		String accessToken,
		InferenceParameters params
	) {
		this.vertx  = vertx;
		this.params = params;
		this.streamId = streamId;
		this.accessToken = accessToken;

		String previewFolderTemplate = "webapps/%s/previews";
		String previewFolderName = String.format(previewFolderTemplate, app.getName());
		File previewFolder = new File(previewFolderName);
		if(!previewFolder.exists()) {
			previewFolder.mkdirs();
		}

		String detectionFolderTemplate = "%s/%s";
		String detectionFolderName = String.format(detectionFolderTemplate, previewFolderName, streamId);
		
		detectionFolder = new File(detectionFolderName);
		if(detectionFolder.exists()) {
			deleteDirectory(detectionFolder);
		}
		detectionFolder.mkdirs();

		this.webSocketClient = new WebSocketClient(params.directaiDomain, params.clientWebhook, detectionFolder, streamId, accessToken, params.localFrameSave, enableTiming);
		String configString = params.model_config.toJson().toJSONString();
		this.webSocketClient.sendJson(configString);

		// Instantiate s3 Client
		if (params.downloadFramesToS3) {
			createS3Client();
		}
	}

	private void deleteDirectory(File directoryToBeDeleted) {
		File[] allContents = directoryToBeDeleted.listFiles();
		if (allContents != null) {
			for (File file : allContents) {
				deleteDirectory(file);
			}
		}
		directoryToBeDeleted.delete();
	}

	@Override
	public AVFrame onAudioFrame(String streamId, AVFrame audioFrame) {
		return audioFrame;
	}

	@Override
	public AVFrame onVideoFrame(String streamId, AVFrame videoFrame) {
		videoFrameCount++;
		LocalDateTime ldt =  LocalDateTime.now();
		String timestamp = ldt.format(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN));
		return processRealTime(streamId, videoFrame, timestamp, params.localFrameSave);	 
	}
	
	public AVFrame processRealTime(String streamId, AVFrame videoFrame, String timestamp, boolean localFrameSave) {
		// since we return yuvFrame, we need to release it in the next call 
		// TODO: find better way
		if(yuvFrame != null) {
			AVFramePool.getInstance().addFrame2Pool(yuvFrame);
		}
		
		int format = videoFrame.format();
		
		AVFrame cloneframe = AVFramePool.getInstance().getAVFrame();
		av_frame_ref(cloneframe, videoFrame);
		
		AVFrame rgbFrame = InferenceUtils.toRGB(cloneframe);
		AVFramePool.getInstance().addFrame2Pool(cloneframe);

		try {
			BufferedImage image = InferenceUtils.avfToBi(rgbFrame);
			if(image != null) {
				logger.info(InferenceUtils.websocketStream(image, timestamp, videoFrameCount, webSocketClient));
				this.trackerInstanceId = webSocketClient.getTrackerInstanceId();
				// Writing to S3
				if (params.downloadFramesToS3 && (this.trackerInstanceId != null)) { // TODO: This may skip the first frame or two. Decide whether we want to wait...
					String fullFramePath = params.s3Path + "/" + streamId + "_" + this.trackerInstanceId + "_" + videoFrameCount + ".jpg";
					InferenceUtils.downloadFramesToS3(s3Client, image, params.s3Bucket, fullFramePath);
				}
				// Writing Locally
				if (localFrameSave) {
					String fileName = "frame_" + videoFrameCount + ".jpg";
					vertx.executeBlocking(a->{
						InferenceUtils.saveRGB(image, detectionFolder.getAbsolutePath()+"/"+fileName, "jpeg");
					},b->{});
				}
				byte[] data = InferenceUtils.getRGBData(image); // TODO: we're doubling work here...
				rgbFrame.data(0, new BytePointer(data));
				yuvFrame = InferenceUtils.toTargetFormat(rgbFrame, format);
				AVFramePool.getInstance().addFrame2Pool(rgbFrame);
				return yuvFrame;
			}
		} catch (IOException e) {
			logger.info(ExceptionUtils.getMessage(e));
		}
		return null;
	}

	@Override
	public void writeTrailer(String streamId) {
	}

	@Override
	public void setVideoStreamInfo(String streamId, StreamParametersInfo videoStreamInfo) {
	}

	@Override
	public void setAudioStreamInfo(String streamId, StreamParametersInfo audioStreamInfo) {
	}

	@Override
	public void start() {
	}

	public void closeWebsocket() {
		this.webSocketClient.closeSession();
		logger.info("Websocket closed.");
	}

	private void createS3Client() {
		AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();

		String accessKey = params.s3AccessKey;
		String secretKey = params.s3SecretKey;
		String region = params.s3Region;
		
		// Create BasicAWSCredentials object using accessKey and secretKey
		BasicAWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);
		
		// Add credentials to AmazonS3ClientBuilder object using the AWSStaticCredentialsProvider
		builder = builder.withCredentials(new AWSStaticCredentialsProvider(awsCredentials));
		builder = builder.withRegion(region);

		s3Client = builder.build();
	}
}