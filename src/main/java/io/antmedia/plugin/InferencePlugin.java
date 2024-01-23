package io.antmedia.plugin;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.plugin.api.IStreamListener;
import io.vertx.core.Vertx;

import org.apache.commons.lang3.exception.ExceptionUtils;

@Component(value="plugin.inference")
public class InferencePlugin implements ApplicationContextAware, IStreamListener{
	protected static Logger logger = LoggerFactory.getLogger(InferencePlugin.class);
	
	private Vertx vertx;
	private ApplicationContext applicationContext;

	private InferenceFrameListener frameListener;

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
		vertx = (Vertx) applicationContext.getBean("vertxCore");
		
		AntMediaApplicationAdapter app = getApplication();
		app.addStreamListener(this);
	}
		
	public boolean startInference(
		String streamId, 
		InferenceParameters params
	) {
		/* 
		Verifying that we're authenticated by DirectAI prior to Beginning the Inference Process
		*/
		logger.info("OBTAINING ACCESS TOKEN");
		// Create a new JSONObject
		JSONObject json = new JSONObject();
		// Add key-value pairs
		json.put("client_id", params.directaiClientId);
		json.put("client_secret", params.directaiClientSecret);

		String authTemplate = "https://%s/token";
		String authURL = String.format(authTemplate, params.directaiDomain);
		String response = InferenceUtils.sendPostRequestWithParams(
			authURL,
			json
		);
		
		String accessToken = "";
		JSONParser parser = new JSONParser();
		try {
			JSONObject resultJson = (JSONObject)parser.parse(response);
			// Checking to see if auth request went smoothly
			if (resultJson.containsKey("access_token")) {
				accessToken = (String)resultJson.get("access_token");
			} else {
				logger.info(response);
			}
		} catch (ParseException e) {
			logger.info(ExceptionUtils.getMessage(e));
		}
		/* 
		Now We Can Run Inference
		*/
		if (accessToken != "") {
			logger.info("***** INFERENCE BEGINNING *****");
			AntMediaApplicationAdapter app = getApplication();

			frameListener = new InferenceFrameListener(
				vertx, 
				app, 
				streamId,
				accessToken,
				params
			);
			app.addFrameListener(streamId, frameListener);
			return true;
		} else {
			return false;
		}
	}	
		

	public boolean stopInference(String streamId) {
        logger.info("***** INFERENCE COMPLETE *****");
		AntMediaApplicationAdapter app = getApplication();
		boolean result = false;
		if(frameListener != null) {
			//TODO: Refactor we need to return a value in the interface method
			frameListener.closeWebsocket();
			app.removeFrameListener(streamId, frameListener);
			result = true;
		}
		return result;
	}
	
	public AntMediaApplicationAdapter getApplication() {
		return (AntMediaApplicationAdapter) applicationContext.getBean(AntMediaApplicationAdapter.BEAN_NAME);
	}

	@Override
	public void streamStarted(String streamId) {
	}

	@Override
	public void streamFinished(String streamId) {
		stopInference(streamId);
	}

	@Override
	public void joinedTheRoom(String roomId, String streamId) {
	}

	@Override
	public void leftTheRoom(String roomId, String streamId) {
	}
}
