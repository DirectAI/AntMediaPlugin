package io.antmedia.plugin;

import javax.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Arrays;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.apache.commons.lang3.exception.ExceptionUtils;

public class WebSocketClient extends Endpoint{
    protected static Logger logger = LoggerFactory.getLogger(WebSocketClient.class);
    private JSONObject returnedObjects = new JSONObject();
    private int frameCount = 0;
    private ArrayList<LocalDateTime> startTimes = new ArrayList<LocalDateTime>();
    private ArrayList<LocalDateTime> endTimes = new ArrayList<LocalDateTime>();
    private ArrayList<Long> latencyValues = new ArrayList<Long>();
    private File detectionFolder;
    private boolean localFrameSave;
    private boolean enableTiming;
    private Session session;
    private LocalDateTime currentTime;
    private String clientWebhook;
    private String streamId;
    private String trackerInstanceId;
    
    public WebSocketClient(
        String directaiDomain, 
        String clientWebhook, 
        File detectionFolder, 
        String streamId, 
        String accessToken, 
        boolean localFrameSave, 
        boolean enableTiming
    ) {
        this.localFrameSave = localFrameSave;
        this.enableTiming = enableTiming;
        this.clientWebhook = clientWebhook;
        this.streamId = streamId;
        this.detectionFolder = detectionFolder;

        ClientEndpointConfig.Configurator configurator = new ClientEndpointConfig.Configurator() {
            @Override
            public void beforeRequest(Map<String, List<String>> headers) {
                headers.put("Authorization", Arrays.asList("Bearer " + accessToken));
            }
        };
        ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
                .configurator(configurator)
                .build();
        URI websocketUri = URI.create("wss://" + directaiDomain + "/ws-tracker");
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(this, config, websocketUri);
        } catch (DeploymentException | IOException e) {
            logger.info(ExceptionUtils.getMessage(e));
        }
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        logger.info("WebSocket connection opened: " + session.getId());
        this.session = session;
        // Add a text message handler to this session
        this.session.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
                withMessage(message);
            }
        });
    }

    @Override
    public void onClose(Session session, CloseReason reason) {
        logger.info("WebSocket connection closed: " + reason);
    }

    @Override
    public void onError(Session session, Throwable t) {
        logger.info(ExceptionUtils.getMessage(t));
    }

    public void withMessage(String message) {
        if(enableTiming) {
            try {
                int frameId = frameIdFromMessage(message);
                this.trackerInstanceId = trackerInstanceIdFromMessage(message);
                currentTime =  LocalDateTime.now();
                latencyValues.add(ChronoUnit.MILLIS.between(startTimes.get(frameId-1), currentTime));
                endTimes.add(currentTime);
                if (frameCount % 50 == 0) {
					double meanValue = calculateMeanValue();
					double variance = calculateVariance(meanValue);
					logger.info("Average Length of API Call over " + String.valueOf(frameCount) + " frames: " + meanValue);
					logger.info("Variance of API Call length over " + String.valueOf(frameCount) + " frames: " + variance);
                    int startTimesSize = startTimes.size();
                    double startTimeDiff = ChronoUnit.MILLIS.between(startTimes.get(0), startTimes.get(startTimesSize-1)) / (double) 1000;
                    logger.info(startTimesSize + " frames STREAMED to the API over " + startTimeDiff + " seconds yielding " + startTimesSize / startTimeDiff + " fps."); 
                    int endTimesSize = endTimes.size();
                    double endTimeDiff = ChronoUnit.MILLIS.between(endTimes.get(0), endTimes.get(endTimesSize-1)) / (double) 1000;
                    logger.info(endTimesSize + " frames RETURNED FROM the API over " + endTimeDiff + " seconds yielding " + endTimesSize / endTimeDiff + " fps."); 
				}
            } catch (ParseException e) {
                logger.info("Message that hit parse exception: "+ message);
                logger.info(ExceptionUtils.getMessage(e));
            }
        }
        
        JSONParser parser = new JSONParser();
        try {
            JSONObject resultJson = (JSONObject)parser.parse(message);
            JSONObject metadata = (JSONObject)resultJson.get("metadata");
            metadata.put("stream_id", streamId);
            if (localFrameSave) {
                // Writing bounding boxes directly for demo purposes
                int frameId = ((Long) metadata.get("frame_id")).intValue();
                returnedObjects.put(frameId, resultJson);
                annotateImage(frameId);
            }
            message = resultJson.toString();
        } catch (ParseException e) {
            logger.info(ExceptionUtils.getMessage(e));
        } catch (NullPointerException e) {
            logger.info(ExceptionUtils.getMessage(e));
        }
        
        if (!clientWebhook.isEmpty()) {
            String postResponse = InferenceUtils.sendPostRequest(clientWebhook, message);
            logger.info(postResponse);
        }
        logger.info(message);
    }

    public void annotateImage(int frameId) {
        JSONObject frameResponse = getResponse(frameId);
        String fileName = "frame_" + frameId + ".jpg";
		String filePath = detectionFolder.getAbsolutePath()+"/"+fileName; 
        JSONArray trackedObjects = (JSONArray) frameResponse.get("other_detections");
        BufferedImage image = obtainImage(filePath);
        for (Object obj : trackedObjects) {
            JSONObject jsonObject = (JSONObject) obj;
            JSONArray tlbr = (JSONArray)jsonObject.get("tlbr");
            logger.info(tlbr.toString());
            int topLeftX = ((Double) tlbr.get(0)).intValue();
            int topLeftY = ((Double) tlbr.get(1)).intValue();
            int bottomRightX = ((Double) tlbr.get(2)).intValue();
            int bottomRightY = ((Double) tlbr.get(3)).intValue();
            image = InferenceUtils.writeRectangles(image,topLeftX,topLeftY,bottomRightX,bottomRightY);
        }
        InferenceUtils.saveRGB(image, filePath, "jpg");
    }

    public BufferedImage obtainImage(String filePath) {
        BufferedImage image = null;
        try {
            File file = new File(filePath);
            image = ImageIO.read(file);
        } catch (IOException e) {
            logger.info(ExceptionUtils.getMessage(e));
        }
        if (image == null) {
            logger.info("Raw image is null.");
        }
        return image;
    }

    public boolean responseExists(int frameId) {
        return returnedObjects.containsKey(frameId);
    }

    public JSONObject getResponse(int frameId) {
        return (JSONObject)returnedObjects.get(frameId);
    }

    public void sendBytes(byte[] data) {
        startTimes.add(LocalDateTime.now());
        if (session != null && session.isOpen()) {
            session.getAsyncRemote().sendBinary(ByteBuffer.wrap(data));
        }
        frameCount++;
    }

    public void sendJson(String jsonString) {
        if (session != null && session.isOpen()) {
            session.getAsyncRemote().sendText(jsonString);
        }
    }

    private int frameIdFromMessage(String message) throws ParseException {
        JSONParser parser = new JSONParser();
        JSONObject resultJson = (JSONObject)parser.parse(message);
        JSONObject metadata = (JSONObject)resultJson.get("metadata");
        return ((Long) metadata.get("frame_id")).intValue();
    }
    
    private String trackerInstanceIdFromMessage(String message) throws ParseException {
        JSONParser parser = new JSONParser();
        JSONObject resultJson = (JSONObject)parser.parse(message);
        JSONObject metadata = (JSONObject)resultJson.get("metadata");
        return (String) metadata.get("tracker_instance_id");
    }

    public String getTrackerInstanceId() {
        return this.trackerInstanceId;
    }

    public double calculateMeanValue() {
        if (latencyValues == null || latencyValues.size() == 0) {
            throw new IllegalArgumentException("The input ArrayList cannot be null or empty.");
        }

        double sum = 0;
        for (Long number : latencyValues) {
            sum += number;
        }

        return sum / latencyValues.size();
    }

    public double calculateVariance(double meanValue) {
        if (latencyValues == null || latencyValues.size() == 0) {
            throw new IllegalArgumentException("The input ArrayList cannot be null or empty.");
        }

        double sumOfSquaredDifferences = 0;
        for (Long number : latencyValues) {
            sumOfSquaredDifferences += Math.pow(number - meanValue, 2);
        }

        return sumOfSquaredDifferences / latencyValues.size();
    }

    public void closeSession() {
        if (session != null && session.isOpen()) {
            session.getAsyncRemote().sendText("STOP");
        }
    }
}
