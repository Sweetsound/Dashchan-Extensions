package com.trixiether.dashchan.chan.warosu;

import android.net.Uri;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import chan.content.ChanLocator;
import chan.content.FoolFuukaChanLocator;
import chan.content.FoolFuukaChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.RedirectException;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.text.ParseException;

public class WarosuChanPerformer  extends FoolFuukaChanPerformer {

    @Override
    public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
        WarosuChanLocator locator = ChanLocator.get(this);
        Uri uri = locator.createBoardUri(data.boardName, data.pageNumber + 1);
        HttpResponse response = new HttpRequest(uri, data).setValidator(data.validator).perform();
        try (InputStream input = response.open()) {
            return new ReadThreadsResult(new WarosuPostsParser(this).convertThreads(input));
        } catch (ParseException e) {
            throw new InvalidResponseException(e);
        } catch (IOException e) {
            throw response.fail(e);
        }
    }

    private static final Pattern PATTERN_REDIRECT = Pattern.compile("You are being redirected to .*?/thread/(\\d+)/#");

    @Override
    public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException,
            RedirectException {
        WarosuChanLocator locator = ChanLocator.get(this);
        Uri uri = locator.createThreadUri(data.boardName, data.threadNumber);
        HttpResponse response = new HttpRequest(uri, data).setValidator(data.validator).setSuccessOnly(false).perform();
        if (response.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
            uri = locator.buildPath(data.boardName, "post", data.threadNumber, "");
            String responseText = new HttpRequest(uri, data).perform().readString();
            Matcher matcher = PATTERN_REDIRECT.matcher(responseText);
            if (matcher.find()) {
                throw RedirectException.toThread(data.boardName, matcher.group(1), data.threadNumber);
            }
            throw HttpException.createNotFoundException();
        } else {
            response.checkResponseCode();
        }
        try (InputStream input = response.open()) {
            // TODO Move to child classes
            Uri threadUri = locator.buildPathWithHost("boards.4chan.org", data.boardName, "thread", data.threadNumber);
            return new ReadPostsResult(new WarosuPostsParser(this).convertPosts(input, threadUri));
        } catch (ParseException e) {
            throw new InvalidResponseException(e);
        } catch (IOException e) {
            throw response.fail(e);
        }
    }

    @Override
    public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
        FoolFuukaChanLocator locator = ChanLocator.get(this);
        HttpResponse response = new HttpRequest(locator.buildPath(), data).perform();
        try (InputStream input = response.open()) {
            return new ReadBoardsResult(new WarosuBoardsParser().convert(input));
        } catch (ParseException e) {
            throw new InvalidResponseException(e);
        } catch (IOException e) {
            throw response.fail(e);
        }
    }

    @Override
    public ReadSearchPostsResult onReadSearchPosts(ReadSearchPostsData data) throws HttpException,
            InvalidResponseException {
        WarosuChanLocator locator = ChanLocator.get(this);
        Uri uri = locator.createSearchUri(data.boardName, data.searchQuery, data.pageNumber);
        HttpResponse response = new HttpRequest(uri, data).perform();
        try (InputStream input = response.open()) {
            return new ReadSearchPostsResult(new WarosuPostsParser(this).convertSearch(input));
        } catch (ParseException e) {
            throw new InvalidResponseException(e);
        } catch (IOException e) {
            throw response.fail(e);
        }
    }
}
