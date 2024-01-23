package io.antmedia.plugin;

import java.util.List;
import java.util.Arrays;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;

public class InferenceParameters {
    public boolean localFrameSave = false;
    public boolean downloadFramesToS3 = false;
    public String s3Bucket = "ant-media-frame-bucket";
    public String s3AccessKey = "";
    public String s3SecretKey = "";
    public String s3Region = "us-east-1";
    public String s3Path = "public_chatroom";
    public String modelId = "ea67ab93-4604-4bab-8737-e8b8f58c68e7";
    public String directaiDomain = "api.free.directai.io";
    public String directaiClientId = "";
    public String directaiClientSecret = "";
    public String clientWebhook = "";
    public ModelConfig model_config = new ModelConfig();

    public static class ModelConfig {
        public List<ObjectConfig> detectors = Arrays.asList(new ObjectConfig());
        public boolean realTime = true;
        public double nms_threshold = 0.4;

        public JSONObject toJson() {
            JSONObject config = new JSONObject();
            config.put("real_time", realTime);
            config.put("nms_threshold", nms_threshold);
            config.put("first_match_thresh", 0);
            config.put("second_match_thresh", 0);
            config.put("third_match_thresh", 0);
            JSONArray detectorsArray = new JSONArray();
            for (ObjectConfig oc : detectors) {
                JSONObject detectorsJSON = new JSONObject();
                detectorsJSON.put("name",oc.name);
                JSONArray incsArray = new JSONArray();
                incsArray.addAll(oc.examples_to_include);
                detectorsJSON.put("incs",incsArray);
                JSONArray excsArray = new JSONArray();
                excsArray.addAll(oc.examples_to_exclude);
                detectorsJSON.put("excs",excsArray);
                detectorsJSON.put("thresh",oc.detection_threshold);
                detectorsArray.add(detectorsJSON);
            }
            config.put("detectors", detectorsArray);
            return config;
        }
    }

    public static class ObjectConfig {
        public String name = "face";
        public List<String> examples_to_include = Arrays.asList(name);
        public List<String> examples_to_exclude = Arrays.asList();
        public double detection_threshold = 0.15;
    }
}