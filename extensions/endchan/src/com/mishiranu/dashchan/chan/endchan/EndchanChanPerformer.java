package com.mishiranu.dashchan.chan.endchan;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import chan.content.ApiException;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.SimpleEntity;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class EndchanChanPerformer extends ChanPerformer {
	private static final String[] BOARDS_GENERAL = {"operate"};

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		if (data.isCatalog()) {
			Uri uri = locator.buildPath(data.boardName, "catalog.json");
			try {
				JSONArray jsonArray = new JSONArray(new HttpRequest(uri, data)
						.setValidator(data.validator).perform().readString());
				if (jsonArray.length() == 0) {
					return null;
				}
				return new ReadThreadsResult(EndchanModelMapper.createThreads(jsonArray, locator));
			} catch (JSONException | ParseException e) {
				throw new InvalidResponseException(e);
			}
		} else {
			Uri uri = locator.buildPath(data.boardName, (data.pageNumber + 1) + ".json");
			try {
				JSONObject jsonObject = new JSONObject(new HttpRequest(uri, data)
						.setValidator(data.validator).perform().readString());
				if (data.pageNumber == 0) {
					EndchanChanConfiguration configuration = EndchanChanConfiguration.get(this);
					configuration.updateFromThreadsJson(data.boardName, jsonObject, true);
				}
				JSONArray jsonArray = jsonObject.optJSONArray("threads");
				if (jsonArray == null || jsonObject.length() == 0) {
					return null;
				}
				return new ReadThreadsResult(EndchanModelMapper.createThreads(jsonArray, locator));
			} catch (JSONException | ParseException e) {
				throw new InvalidResponseException(e);
			}
		}
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		Uri uri = locator.buildPath(data.boardName, "res", data.threadNumber + ".json");
		try {
			JSONObject jsonObject = new JSONObject(new HttpRequest(uri, data)
					.setValidator(data.validator).perform().readString());
			return new ReadPostsResult(EndchanModelMapper.createPosts(jsonObject, locator));
		} catch (JSONException | ParseException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		EndchanChanConfiguration configuration = EndchanChanConfiguration.get(this);
		Board[] boards = new Board[BOARDS_GENERAL.length];
		try {
			for (int i = 0; i < BOARDS_GENERAL.length; i++) {
				Uri uri = locator.buildPath(BOARDS_GENERAL[i], "1.json");
				JSONObject jsonObject = new JSONObject(new HttpRequest(uri, data).perform().readString());
				String title = CommonUtils.getJsonString(jsonObject, "boardName");
				String description = CommonUtils.optJsonString(jsonObject, "boardDescription");
				configuration.updateFromThreadsJson(BOARDS_GENERAL[i], jsonObject, false);
				boards[i] = new Board(BOARDS_GENERAL[i], title, description);
			}
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
		return new ReadBoardsResult(new BoardCategory("General", boards));
	}

	@Override
	public ReadUserBoardsResult onReadUserBoards(ReadUserBoardsData data) throws HttpException,
			InvalidResponseException {
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		Uri uri = locator.buildQuery("boards.js", "json", "1");
		try {
			JSONObject jsonObject = new JSONObject(new HttpRequest(uri, data).perform().readString());
			HashSet<String> ignoreBoardNames = new HashSet<>();
			Collections.addAll(ignoreBoardNames, BOARDS_GENERAL);
			ArrayList<Board> boards = new ArrayList<>();
			for (int i = 0, count = jsonObject.getInt("pageCount"); i < count; i++) {
				if (i > 0) {
					uri = locator.buildQuery("boards.js", "json", "1", "page", Integer.toString(i + 1));
					jsonObject = new JSONObject(new HttpRequest(uri, data).perform().readString());
				}
				JSONArray jsonArray = jsonObject.getJSONArray("boards");
				for (int j = 0; j < jsonArray.length(); j++) {
					jsonObject = jsonArray.getJSONObject(j);
					String boardName = CommonUtils.getJsonString(jsonObject, "boardUri");
					if (!ignoreBoardNames.contains(boardName)) {
						String title = CommonUtils.getJsonString(jsonObject, "boardName");
						String description = CommonUtils.getJsonString(jsonObject, "boardDescription");
						boards.add(new Board(boardName, title, description));
					}
				}
			}
			return new ReadUserBoardsResult(boards);
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	private static final String REQUIRE_REPORT = "report";
	private static final String REQUIRE_IP_BLOCK_BYPASS = "ip_block_bypass";

	@Override
	public ReadCaptchaResult onReadCaptcha(ReadCaptchaData data) throws HttpException, InvalidResponseException {
		boolean needCaptcha = false;
		if (REQUIRE_REPORT.equals(data.requirement) || REQUIRE_IP_BLOCK_BYPASS.equals(data.requirement)) {
			needCaptcha = true;
		} else {
			EndchanChanLocator locator = EndchanChanLocator.get(this);
			Uri uri;
			if (!StringUtils.isEmpty(data.threadNumber)) {
				uri = locator.createThreadUri(data.boardName, data.threadNumber);
			} else {
				uri = locator.createBoardUri(data.boardName, 0);
			}
			String responseText = new HttpRequest(uri, data).perform().readString();
			if (responseText.contains("<div id=\"captchaDiv\">")) {
				needCaptcha = true;
			}
		}

		if (needCaptcha) {
			EndchanChanLocator locator = EndchanChanLocator.get(this);
			Uri uri = locator.buildPath("captcha.js");
			HttpResponse response = new HttpRequest(uri, data).perform();
			Bitmap image = response.readBitmap();
			String captchaId = response.getCookieValue("captchaid");
			if (image == null || captchaId == null) {
				throw new InvalidResponseException();
			}

			int[] pixels = new int[image.getWidth() * image.getHeight()];
			image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
			for (int i = 0; i < pixels.length; i++) {
				pixels[i] = (0xff - Math.max(Color.red(pixels[i]), Color.blue(pixels[i]))) << 24;
			}
			Bitmap newImage = Bitmap.createBitmap(pixels, image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
			image.recycle();
			image = CommonUtils.trimBitmap(newImage, 0x00000000);
			if (image == null) {
				image = newImage;
			} else if (image != newImage) {
				newImage.recycle();
			}

			CaptchaData captchaData = new CaptchaData();
			captchaData.put(CaptchaData.CHALLENGE, captchaId);
			return new ReadCaptchaResult(CaptchaState.CAPTCHA, captchaData).setImage(image);
		} else {
			return new ReadCaptchaResult(CaptchaState.SKIP, null);
		}
	}

	private String trimPassword(String password) {
		// Max password length: 8
		return password != null && password.length() > 8 ? password.substring(0, 8) : password;
	}

	@Override
	public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException, InvalidResponseException {
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		SimpleEntity entity = new SimpleEntity();
		entity.setContentType("application/json; charset=utf-8");
		JSONObject jsonObject = new JSONObject();
		JSONObject parametersObject = new JSONObject();
		EndchanChanConfiguration configuration = EndchanChanConfiguration.get(this);
		String ipBlockBypassKey = "ip_block_bypass_key";
		String ipBlockBypassId = configuration.get(null, ipBlockBypassKey, null);
		try {
			try {
				if (!StringUtils.isEmpty(ipBlockBypassId)){
					jsonObject.put("bypassId", ipBlockBypassId);
				}
				jsonObject.put("parameters", parametersObject);
				parametersObject.put("boardUri", data.boardName);
				if (data.threadNumber != null) {
					parametersObject.put("threadId", data.threadNumber);
				}
				if (data.name != null) {
					parametersObject.put("name", data.name);
				}
				if (data.subject != null) {
					parametersObject.put("subject", data.subject);
				}
				if (data.password != null) {
					parametersObject.put("password", trimPassword(data.password));
				}
				parametersObject.put("message", StringUtils.emptyIfNull(data.comment));
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}

			String captchaId = data.captchaData != null ? data.captchaData.get(CaptchaData.CHALLENGE) : null;
			if (captchaId != null) {
				try {
					jsonObject.put("captchaId", captchaId);
					parametersObject.put("captcha", StringUtils.emptyIfNull(data.captchaData.get(CaptchaData.INPUT)));
				} catch (JSONException e) {
					throw new RuntimeException(e);
				}
			}

			if (data.attachments != null) {
				JSONArray jsonArray = new JSONArray();
				MessageDigest messageDigest;
				try {
					messageDigest = MessageDigest.getInstance("MD5");
				} catch (NoSuchAlgorithmException e) {
					throw new RuntimeException(e);
				}
				for (int i = 0; i < data.attachments.length; i++) {
					SendPostData.Attachment attachment = data.attachments[i];
					byte[] bytes = new byte[(int) attachment.getSize()];
					try (InputStream inputStream = attachment.openInputSteam()) {
						inputStream.read(bytes);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					messageDigest.reset();
					byte[] digest = messageDigest.digest(bytes);
					StringBuilder digestBuilder = new StringBuilder();
					for (byte b : digest) {
						digestBuilder.append(String.format(Locale.US, "%02x", b));
					}
					Uri uri = locator.buildQuery("checkFileIdentifier.js", "identifier", digestBuilder + "-"
							+ attachment.getMimeType().replace("/", ""));
					String responseText = new HttpRequest(uri, data).perform().readString();
					JSONObject fileObject = new JSONObject();
					try {
						if ("false".equals(responseText)) {
							fileObject.put("content", "data:" + attachment.getMimeType() + ";base64,"
									+ Base64.encodeToString(bytes, Base64.NO_WRAP));
						} else if ("true".equals(responseText)) {
							fileObject.put("mime", attachment.getMimeType());
							fileObject.put("md5", digestBuilder.toString());
						} else {
							throw new InvalidResponseException();
						}
						fileObject.put("name", attachment.getFileName());
						if (attachment.optionSpoiler) {
							fileObject.put("spoiler", true);
						}
						jsonArray.put(fileObject);
					} catch (JSONException e) {
						throw new RuntimeException(e);
					}
				}
				try {
					parametersObject.put("files", jsonArray);
				} catch (JSONException e) {
					throw new RuntimeException(e);
				}
			}
			entity.setData(jsonObject.toString());
		} catch (OutOfMemoryError e) {
			throw new RuntimeException(e);
		}

		Uri uri = locator.buildPath(".api", data.threadNumber != null ? "replyThread" : "newThread");
		try {
			jsonObject = new JSONObject(new HttpRequest(uri, data).setPostMethod(entity)
					.setRedirectHandler(HttpRequest.RedirectHandler.STRICT).perform().readString());
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}

		String status = jsonObject.optString("status");
		if ("ok".equals(status)) {
			String postNumber = Integer.toString(jsonObject.optInt("data"));
			String threadNumber = data.threadNumber;
			if (threadNumber == null) {
				threadNumber = postNumber;
				postNumber = null;
			}
			CommonUtils.sleepMaxRealtime(SystemClock.elapsedRealtime(), 2000);
			return new SendPostResult(threadNumber, postNumber);
		}
		if ("bypassable".equals(status)) {
			JSONObject ipBlockBypassResult = bypassIpBlock(data);
			if(ipBlockBypassResult != null) {
				status = ipBlockBypassResult.optString("status");
				if("ok".equals(status)){
					String blockBypassId = ipBlockBypassResult.optString("data");
					configuration.set(null, ipBlockBypassKey, blockBypassId);
					return onSendPost(data); // If there was IP block, the captcha that has been used to send the post is still valid (if not expired), and we can retry to send the post without solving a new captcha
				}
			}
			else {
				String ipBlockBypassFailedMessage = configuration.getResources().getString(R.string.ip_block_bypass_failed);
				throw new ApiException(ipBlockBypassFailedMessage);
			}
		}
		if("banned".equals(status)){
			throw new ApiException(ApiException.SEND_ERROR_BANNED);
		}
		if (!"error".equals(status) && !"blank".equals(status)) {
			CommonUtils.writeLog("Endchan send message", jsonObject.toString());
			throw new InvalidResponseException();
		}
		String errorMessage = jsonObject.optString("data");
		int errorType = 0;
		if (errorMessage.contains("Wrong captcha") || errorMessage.contains("Expired captcha")) {
			errorType = ApiException.SEND_ERROR_CAPTCHA;
		} else if (errorMessage.contains("Flood detected")) {
			errorType = ApiException.SEND_ERROR_TOO_FAST;
		} else if (errorMessage.contains("Either a message or a file is required")
				|| "message".equals(errorMessage)) {
			errorType = ApiException.SEND_ERROR_EMPTY_COMMENT;
		} else if (errorMessage.contains("Board not found")) {
			errorType = ApiException.SEND_ERROR_NO_BOARD;
		} else if (errorMessage.contains("Thread not found")) {
			errorType = ApiException.SEND_ERROR_NO_THREAD;
		}
		if (errorType != 0) {
			throw new ApiException(errorType);
		}
		CommonUtils.writeLog("Endchan send message", status, errorMessage);
		throw new ApiException(status + ": " + errorMessage);
	}

	private JSONObject bypassIpBlock(HttpRequest.Preset preset) throws HttpException {
		CaptchaData captchaData = requireUserCaptcha(REQUIRE_IP_BLOCK_BYPASS, null, null, false);
		if(captchaData == null) return null;

		String captchaInput = captchaData.get(CaptchaData.INPUT);
		String captchaId = captchaData.get(CaptchaData.CHALLENGE);

		if (StringUtils.isEmpty(captchaInput) || StringUtils.isEmpty(captchaId)) {
			return null;
		}
		try {
			JSONObject requestParameters = new JSONObject();
			requestParameters.put("captcha", captchaInput);

			JSONObject requestJsonObject = new JSONObject();
			requestJsonObject.put("parameters", requestParameters);
			requestJsonObject.put("captchaId", captchaId);

			SimpleEntity requestEntity = new SimpleEntity();
			requestEntity.setContentType("application/json; charset=utf-8");
			requestEntity.setData(requestJsonObject.toString());

			EndchanChanLocator locator = EndchanChanLocator.get(this);
			Uri renewBypassApiUri = locator.buildPath(".api", "renewBypass");

			HttpResponse response = new HttpRequest(renewBypassApiUri, preset)
					.setPostMethod(requestEntity)
					.setRedirectHandler(HttpRequest.RedirectHandler.STRICT)
					.perform();

			return new JSONObject(response.readString());
		}
		catch (JSONException jsonException){
			CommonUtils.writeLog("Endchan", "ip block bypass json exception", jsonException.getMessage());
			return null;
		}
	}

	private static void fillDeleteReportPostings(JSONObject parametersObject, String boardName, String threadNumber,
			Collection<String> postNumbers) throws JSONException {
		JSONArray jsonArray = new JSONArray();
		for (String postNumber : postNumbers) {
			JSONObject postObject = new JSONObject();
			postObject.put("board", boardName);
			postObject.put("thread", threadNumber);
			if (!postNumber.equals(threadNumber)) {
				postObject.put("post", postNumber);
			}
			jsonArray.put(postObject);
		}
		parametersObject.put("postings", jsonArray);
	}

	@Override
	public SendDeletePostsResult onSendDeletePosts(SendDeletePostsData data) throws HttpException, ApiException,
			InvalidResponseException {
		JSONObject jsonObject = new JSONObject();
		JSONObject parametersObject = new JSONObject();
		try {
			jsonObject.put("parameters", parametersObject);
			parametersObject.put("password", trimPassword(data.password));
			parametersObject.put("deleteMedia", true);
			if (data.optionFilesOnly) {
				parametersObject.put("deleteUploads", true);
			}
			fillDeleteReportPostings(parametersObject, data.boardName, data.threadNumber, data.postNumbers);
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
		SimpleEntity entity = new SimpleEntity();
		entity.setContentType("application/json; charset=utf-8");
		entity.setData(jsonObject.toString());
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		Uri uri = locator.buildPath(".api", "deleteContent");
		try {
			jsonObject = new JSONObject(new HttpRequest(uri, data).setPostMethod(entity)
					.setRedirectHandler(HttpRequest.RedirectHandler.STRICT).perform().readString());
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
		if ("error".equals(CommonUtils.optJsonString(jsonObject, "status"))) {
			String errorMessage = CommonUtils.optJsonString(jsonObject, "data");
			if (errorMessage != null) {
				if (errorMessage.contains("Invalid account")) {
					throw new ApiException(ApiException.DELETE_ERROR_PASSWORD);
				}
				CommonUtils.writeLog("Endchan delete message", errorMessage);
				throw new ApiException(errorMessage);
			}
		}
		try {
			jsonObject = jsonObject.getJSONObject("data");
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
		if (jsonObject.optInt("removedThreads") + jsonObject.optInt("removedPosts") > 0) {
			return new SendDeletePostsResult();
		} else {
			throw new ApiException(ApiException.DELETE_ERROR_PASSWORD);
		}
	}

	@Override
	public SendReportPostsResult onSendReportPosts(SendReportPostsData data) throws HttpException, ApiException,
			InvalidResponseException {
		JSONObject jsonObject = new JSONObject();
		JSONObject parametersObject = new JSONObject();
		try {
			jsonObject.put("parameters", parametersObject);
			parametersObject.put("reason", StringUtils.emptyIfNull(data.comment));
			if (data.options.contains("global")) {
				parametersObject.put("global", true);
			}
			fillDeleteReportPostings(parametersObject, data.boardName, data.threadNumber, data.postNumbers);
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
		boolean retry = false;
		while (true) {
			CaptchaData captchaData = requireUserCaptcha(REQUIRE_REPORT, data.boardName, data.threadNumber, retry);
			if (captchaData == null) {
				throw new ApiException(ApiException.REPORT_ERROR_NO_ACCESS);
			}
			try {
				jsonObject.put("captchaId", captchaData.get(CaptchaData.CHALLENGE));
				parametersObject.put("captcha", StringUtils.emptyIfNull(captchaData.get(CaptchaData.INPUT)));
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
			retry = true;
			SimpleEntity entity = new SimpleEntity();
			entity.setContentType("application/json; charset=utf-8");
			entity.setData(jsonObject.toString());
			EndchanChanLocator locator = EndchanChanLocator.get(this);
			Uri uri = locator.buildPath(".api", "reportContent");
			JSONObject responseJsonObject;
			try {
				responseJsonObject = new JSONObject(new HttpRequest(uri, data).setPostMethod(entity)
						.setRedirectHandler(HttpRequest.RedirectHandler.STRICT).perform().readString());
			} catch (JSONException e) {
				throw new InvalidResponseException(e);
			}
			String status = CommonUtils.optJsonString(responseJsonObject, "status");
			if ("ok".equals(status)) {
				return null;
			}
			String errorMessage = CommonUtils.optJsonString(responseJsonObject, "data");
			if (errorMessage != null) {
				if (errorMessage.contains("Wrong captcha") || errorMessage.contains("Expired captcha")) {
					continue;
				}
				CommonUtils.writeLog("Endchan report message", status, errorMessage);
				throw new ApiException(status + ": " + errorMessage);
			}
			throw new InvalidResponseException();
		}
	}
}
