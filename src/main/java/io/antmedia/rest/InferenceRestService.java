package io.antmedia.rest;

import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import java.util.List;
import java.util.Arrays;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import io.antmedia.plugin.InferencePlugin;
import io.antmedia.plugin.InferenceParameters;
import io.antmedia.rest.model.Result;
import io.swagger.annotations.ApiParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Path("/v2/inference")
public class InferenceRestService {

	@Context
	protected ServletContext servletContext;
	protected static Logger logger = LoggerFactory.getLogger(InferenceRestService.class);

	/*
	 * Start inference pipeline for the given stream id
	 */
	@POST
	@Path("/{streamId}/start")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response start(
		@PathParam("streamId") String streamId, 
		InferenceParameters params
	) {
		if (params == null) {
			return Response.status(Status.BAD_REQUEST).entity("Invalid or missing parameters in the request").build();
		}
		if (!params.downloadFramesToS3) {
			params.s3Bucket = "";
		}

		InferencePlugin app = getPluginApp();
		boolean result = app.startInference(
			streamId, 
			params
		);
		logger.info("we have started inference and are about to return a response");

		return Response.status(Status.OK).entity(new Result(result)).build();
	}
		
	/*
	 * Stop inference pipeline for the given stream id
	 */
	@POST
	@Path("/{streamId}/stop")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response stop(@PathParam("streamId") String streamId) {
		logger.info("inside stop for inference");
		InferencePlugin app = getPluginApp();
		boolean result = app.stopInference(streamId);

		return Response.status(Status.OK).entity(new Result(result)).build();
	}
	
	private InferencePlugin getPluginApp() {
		ApplicationContext appCtx = (ApplicationContext) servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
		return (InferencePlugin) appCtx.getBean("plugin.inference");
	}
}